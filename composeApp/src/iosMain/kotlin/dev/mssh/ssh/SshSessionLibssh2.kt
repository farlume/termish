package dev.mssh.ssh

import dev.mssh.crypto.Sha256
import dev.mssh.util.base64Encode
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.pointed
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.reinterpret
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import libssh2.LIBSSH2_ERROR_EAGAIN
import libssh2.LIBSSH2_HOSTKEY_TYPE_DSS
import libssh2.LIBSSH2_HOSTKEY_TYPE_ECDSA_256
import libssh2.LIBSSH2_HOSTKEY_TYPE_ECDSA_384
import libssh2.LIBSSH2_HOSTKEY_TYPE_ECDSA_521
import libssh2.LIBSSH2_HOSTKEY_TYPE_ED25519
import libssh2.LIBSSH2_HOSTKEY_TYPE_RSA
import libssh2.LIBSSH2_CHANNEL
import libssh2.LIBSSH2_SESSION
import libssh2._LIBSSH2_USERAUTH_KBDINT_PROMPT
import libssh2._LIBSSH2_USERAUTH_KBDINT_RESPONSE
import libssh2.libssh2_channel_close
import libssh2.libssh2_channel_free
import libssh2.libssh2_channel_open_ex
import libssh2.libssh2_channel_process_startup
import libssh2.libssh2_channel_read_ex
import libssh2.libssh2_channel_request_pty_ex
import libssh2.libssh2_channel_request_pty_size_ex
import libssh2.libssh2_channel_write_ex
import libssh2.libssh2_init
import libssh2.libssh2_session_banner_get
import libssh2.libssh2_session_disconnect_ex
import libssh2.libssh2_session_free
import libssh2.libssh2_session_handshake
import libssh2.libssh2_session_hostkey
import libssh2.libssh2_session_init_ex
import libssh2.libssh2_session_last_error
import libssh2.libssh2_userauth_keyboard_interactive_ex
import libssh2.libssh2_userauth_password_ex
import libssh2.libssh2_userauth_publickey_frommemory
import platform.posix.IPPROTO_TCP
import platform.posix.SOCK_STREAM
import platform.posix.close
import platform.posix.connect
import platform.posix.errno
import platform.posix.getaddrinfo
import platform.posix.freeaddrinfo
import platform.posix.gai_strerror
import platform.posix.malloc
import platform.posix.socket
import platform.posix.addrinfo
import kotlin.concurrent.Volatile

/** keyboard-interactive 回调的全局处理器（staticCFunction 不能捕获变量）。 */
@Volatile
private var kbiHandler: (suspend (AuthPrompt) -> List<String>?)? = null

@OptIn(ExperimentalForeignApi::class)
private val kbiCallback = staticCFunction { name: CPointer<ByteVar>?, nameLen: Int,
    instruction: CPointer<ByteVar>?, instructionLen: Int,
    numPrompts: Int,
    prompts: CPointer<_LIBSSH2_USERAUTH_KBDINT_PROMPT>?,
    responses: CPointer<_LIBSSH2_USERAUTH_KBDINT_RESPONSE>?,
    _abstract: CPointer<CPointerVar<out kotlinx.cinterop.CPointed>>? ->
    val nameStr = name?.readBytes(nameLen)?.toKString() ?: ""
    val instrStr = instruction?.readBytes(instructionLen)?.toKString() ?: ""
    val fields = ArrayList<PromptField>()
    for (i in 0 until numPrompts) {
        val p = prompts?.get(i) ?: continue
        val textPtr = p.text?.reinterpret<ByteVar>()
        val text = if (textPtr != null) textPtr.readBytes(p.length.toInt()).toKString() else ""
        fields.add(PromptField(text, p.echo.toInt() != 0))
    }
    val answers = kbiHandler?.let { h ->
        runBlocking { h(AuthPrompt(SshAuthMethod.KEYBOARD_INTERACTIVE, nameStr, instrStr, fields)) }
    }
    for (i in 0 until numPrompts) {
        val resp = responses?.get(i) ?: continue
        val ans = answers?.getOrNull(i) ?: ""
        val bytes = ans.encodeToByteArray()
        val buf = malloc((bytes.size + 1).toULong())?.reinterpret<ByteVar>()
        if (buf != null) {
            for (j in bytes.indices) buf[j] = bytes[j]
            buf[bytes.size] = 0
            resp.text = buf
            resp.length = bytes.size.toUInt()
        } else {
            resp.text = null
            resp.length = 0u
        }
    }
}

/**
 * iOS 引擎：libssh2（静态链接 OpenSSL）。见 scripts/build-ios-native.sh。
 */
@OptIn(ExperimentalForeignApi::class)
class SshSessionLibssh2(
    private val connection: SshConnection,
    private val callbacks: SshCallbacks,
) : SshSession {

    private var session: CPointer<LIBSSH2_SESSION>? = null
    private var channel: CPointer<LIBSSH2_CHANNEL>? = null
    private var sock: Int = -1

    @Volatile
    private var closed = false

    private var hostKeyInfo: HostKeyInfo? = null

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    companion object {
        init {
            libssh2_init(0)
        }
    }

    override fun connectAndStart(columns: Int, rows: Int): SessionInfo {
        val (s, banner) = connectAndAuthenticate()

        val ch = libssh2_channel_open_ex(s, "session", 7u, (2u * 1024u * 1024u), 32768u, null, 0u)
            ?: run { cleanup(); throw SshException("打开会话通道失败") }
        channel = ch
        val term = "xterm-256color"
        if (libssh2_channel_request_pty_ex(ch, term, term.length.toUInt(), null, 0u, columns, rows, 0, 0) != 0) {
            cleanup()
            throw SshException("PTY 请求失败")
        }
        if (libssh2_channel_process_startup(ch, "shell", 5u, null, 0u) != 0) {
            cleanup()
            throw SshException("启动 shell 失败")
        }
        startReader(ch)
        val hk = hostKeyInfo
        return SessionInfo(banner, hk, hk?.algorithm ?: "")
    }

    /** 建立 TCP + SSH 握手 + 主机密钥校验 + 认证，返回会话与 banner。 */
    private fun connectAndAuthenticate(): Pair<CPointer<LIBSSH2_SESSION>?, String> {
        sock = tcpConnect(connection.host, connection.port)
        val s = libssh2_session_init_ex(null, null, null, null) ?: throw SshException("libssh2 初始化失败")
        session = s
        if (libssh2_session_handshake(s, sock) != 0) {
            throw SshException("SSH 握手失败: ${lastError(s)}")
        }
        val banner = libssh2_session_banner_get(s)?.toKString() ?: ""

        val hostKey = computeHostKey(s)
        hostKeyInfo = hostKey
        if (!callbacks.verifyHostKey(hostKey)) {
            cleanup()
            throw SshException("主机密钥验证未通过")
        }
        if (!authenticate(s)) {
            cleanup()
            throw SshException("认证失败")
        }
        return s to banner
    }

    // ---------- 认证 ----------

    private fun authenticate(s: CPointer<LIBSSH2_SESSION>?): Boolean {
        connection.privateKeyPem?.let { pem ->
            val rc = libssh2_userauth_publickey_frommemory(
                s, connection.username, connection.username.length.toULong(),
                null, 0u, pem, pem.length.toULong(), "",
            )
            if (rc == 0) return true
        }
        connection.password?.let { pw ->
            if (libssh2_userauth_password_ex(
                    s, connection.username, connection.username.length.toUInt(),
                    pw, pw.length.toUInt(), null,
                ) == 0
            ) {
                return true
            }
        }
        kbiHandler = callbacks::onPrompt
        val rc = libssh2_userauth_keyboard_interactive_ex(
            s, connection.username, connection.username.length.toUInt(), kbiCallback,
        )
        return rc == 0
    }

    // ---------- 数据收发 ----------

    private fun startReader(ch: CPointer<LIBSSH2_CHANNEL>?) {
        scope.launch {
            try {
                val buf = ByteArray(64 * 1024)
                while (!closed) {
                    val n = buf.usePinned { pinned ->
                        libssh2_channel_read_ex(ch, 0, pinned.addressOf(0), buf.size.toULong())
                    }
                    when {
                        n > 0 -> callbacks.onOutput(buf.copyOf(n.toInt()))
                        n == 0L -> break
                        n.toInt() == LIBSSH2_ERROR_EAGAIN -> continue
                        else -> break
                    }
                }
            } catch (_: Exception) {
            }
            handleClosed()
        }
    }

    private fun handleClosed() {
        if (closed) return
        closed = true
        callbacks.onClosed(null)
    }

    override fun resize(columns: Int, rows: Int, widthPx: Int, heightPx: Int) {
        channel?.let { libssh2_channel_request_pty_size_ex(it, columns, rows, widthPx, heightPx) }
    }

    override fun sendData(data: ByteArray) {
        val ch = channel ?: return
        if (closed || data.isEmpty()) return
        // 终端输入均为 UTF-8 文本；libssh2 的 write 以显式长度写入
        val text = data.decodeToString()
        val rc = libssh2_channel_write_ex(ch, 0, text, data.size.toULong())
        if (rc < 0 && rc.toInt() != LIBSSH2_ERROR_EAGAIN) {
            // 写入失败，忽略（连接可能已断开）
        }
    }

    override fun connectAndRun(command: String, timeoutMs: Long): CommandResult {
        // 非交互 exec 通道：用于 mosh-server 引导等一次性命令
        return try {
            val (s, _) = connectAndAuthenticate()
            val ch = libssh2_channel_open_ex(s, "session", 7u, (2u * 1024u * 1024u), 32768u, null, 0u)
                ?: throw SshException("打开 exec 通道失败")
            if (libssh2_channel_process_startup(ch, "exec", 4u, command, command.length.toUInt()) != 0) {
                libssh2_channel_close(ch)
                libssh2_channel_free(ch)
                throw SshException("启动命令失败: ${lastError(s)}")
            }
            val chunks = ArrayList<ByteArray>()
            val buf = ByteArray(64 * 1024)
            val deadline = Clock.System.now().toEpochMilliseconds() + timeoutMs
            while (Clock.System.now().toEpochMilliseconds() < deadline) {
                val n = buf.usePinned { pinned ->
                    libssh2_channel_read_ex(ch, 0, pinned.addressOf(0), buf.size.toULong())
                }
                when {
                    n > 0L -> chunks.add(buf.copyOf(n.toInt()))
                    n == 0L -> break
                    n.toInt() == LIBSSH2_ERROR_EAGAIN -> continue
                    else -> break
                }
            }
            libssh2_channel_close(ch)
            libssh2_channel_free(ch)
            val total = chunks.sumOf { it.size }
            val all = ByteArray(total)
            var off = 0
            for (c in chunks) {
                c.copyInto(all, off)
                off += c.size
            }
            CommandResult(all.decodeToString(), hostKeyInfo)
        } finally {
            cleanup()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        cleanup()
        callbacks.onClosed(null)
    }

    override fun isActive(): Boolean = !closed

    // ---------- 工具 ----------

    private fun cleanup() {
        channel?.let {
            libssh2_channel_close(it)
            libssh2_channel_free(it)
        }
        channel = null
        session?.let {
            libssh2_session_disconnect_ex(it, 11, "Bye", "")
            libssh2_session_free(it)
        }
        session = null
        if (sock >= 0) {
            close(sock)
            sock = -1
        }
    }

    private fun lastError(s: CPointer<LIBSSH2_SESSION>?): String = memScoped {
        val err = alloc<CPointerVar<ByteVar>>()
        val errLen = alloc<IntVar>()
        libssh2_session_last_error(s, err.ptr, errLen.ptr, 0)
        err.value?.readBytes(errLen.value)?.toKString() ?: "未知错误"
    }

    private fun computeHostKey(s: CPointer<LIBSSH2_SESSION>?): HostKeyInfo = memScoped {
        val len = alloc<ULongVar>()
        val type = alloc<IntVar>()
        val keyPtr = libssh2_session_hostkey(s, len.ptr, type.ptr)
        val keyLen = len.value.toInt()
        val typeName = hostKeyTypeName(type.value)
        if (keyPtr == null || keyLen <= 0) {
            return@memScoped HostKeyInfo(typeName, "SHA256:", "")
        }
        val keyBytes = keyPtr.readBytes(keyLen)
        val typeBytes = typeName.encodeToByteArray()
        val blob = ByteArray(4 + typeBytes.size + keyBytes.size)
        var v = typeBytes.size.toLong()
        for (i in 0 until 4) blob[i] = (v ushr (24 - 8 * i)).toByte()
        typeBytes.copyInto(blob, 4)
        keyBytes.copyInto(blob, 4 + typeBytes.size)
        HostKeyInfo(typeName, "SHA256:" + base64Encode(Sha256.digest(blob)), "")
    }

    private fun hostKeyTypeName(type: Int): String = when (type) {
        LIBSSH2_HOSTKEY_TYPE_RSA -> "ssh-rsa"
        LIBSSH2_HOSTKEY_TYPE_DSS -> "ssh-dss"
        LIBSSH2_HOSTKEY_TYPE_ECDSA_256 -> "ecdsa-sha2-nistp256"
        LIBSSH2_HOSTKEY_TYPE_ECDSA_384 -> "ecdsa-sha2-nistp384"
        LIBSSH2_HOSTKEY_TYPE_ECDSA_521 -> "ecdsa-sha2-nistp521"
        LIBSSH2_HOSTKEY_TYPE_ED25519 -> "ssh-ed25519"
        else -> "unknown"
    }

    // ---------- POSIX TCP ----------

    private fun tcpConnect(host: String, port: Int): Int = memScoped {
        val hints = alloc<addrinfo>()
        hints.ai_family = 0
        hints.ai_socktype = SOCK_STREAM
        hints.ai_protocol = IPPROTO_TCP
        val res = alloc<CPointerVar<addrinfo>>()
        val gai = getaddrinfo(host, port.toString(), hints.ptr, res.ptr)
        if (gai != 0) {
            throw SshException("DNS 解析失败: ${gai_strerror(gai)?.toKString()}")
        }
        var fd = -1
        var cur = res.value
        var lastErr = 0
        while (cur != null) {
            val s = socket(cur.pointed.ai_family, cur.pointed.ai_socktype, cur.pointed.ai_protocol)
            if (s < 0) {
                lastErr = errno
                cur = cur.pointed.ai_next
                continue
            }
            if (connect(s, cur.pointed.ai_addr, cur.pointed.ai_addrlen) == 0) {
                fd = s
                break
            }
            lastErr = errno
            close(s)
            cur = cur.pointed.ai_next
        }
        freeaddrinfo(res.value)
        if (fd < 0) throw SshException("无法连接 $host:$port (errno $lastErr)")
        fd
    }
}

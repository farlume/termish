package dev.termish.ssh

import dev.termish.crypto.Sha256
import dev.termish.util.base64Encode
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
import libssh2.libssh2_channel_get_exit_status
import libssh2.libssh2_channel_open_ex
import libssh2.libssh2_channel_process_startup
import libssh2.libssh2_channel_read_ex
import libssh2.libssh2_channel_request_pty_ex
import libssh2.libssh2_channel_request_pty_size_ex
import libssh2.libssh2_channel_write_ex
import libssh2.libssh2_init
import libssh2.libssh2_keepalive_config
import libssh2.libssh2_keepalive_send
import libssh2.libssh2_session_set_timeout
import libssh2.libssh2_session_set_blocking
import libssh2.libssh2_sftp_init
import libssh2.LIBSSH2_SFTP
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
import platform.posix.POLLERR
import platform.posix.POLLHUP
import platform.posix.POLLOUT
import platform.posix.SOCK_STREAM
import platform.posix.EINPROGRESS
import platform.posix.EISCONN
import platform.posix.ETIMEDOUT
import platform.posix.F_GETFL
import platform.posix.F_SETFL
import platform.posix.O_NONBLOCK
import platform.posix.close
import platform.posix.connect
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.getaddrinfo
import platform.posix.freeaddrinfo
import platform.posix.gai_strerror
import platform.posix.malloc
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.socket
import platform.posix.usleep
import platform.posix.addrinfo
import kotlin.concurrent.Volatile

/** keyboard-interactive 回调的全局处理器（staticCFunction 不能捕获变量）。 */
@Volatile
private var kbiHandler: (suspend (AuthPrompt) -> List<String>?)? = null

/** kbiHandler 是进程级单例：多会话并发 KBI 认证时串行化，避免 prompt 路由到错误会话。 */
private val kbiMutex = Mutex()

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

    /** 读循环协程：close 时先等它退出再释放 libssh2 资源，避免 use-after-free。 */
    private var readerJob: Job? = null
    private var lastKeepaliveMs: Long = 0

    private var hostKeyInfo: HostKeyInfo? = null
    private var authFailureReason: String? = null

    // 单线程串行调度器：libssh2 会话非线程安全，读循环 / 写 / resize / keepalive /
    // cleanup 全部编组到同一线程，杜绝并发进入同一 LIBSSH2_SESSION。
    private val serialDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(serialDispatcher + SupervisorJob())

    companion object {
        init {
            libssh2_init(0)
        }
    }

    override fun connectAndStart(columns: Int, rows: Int): SessionInfo {
        val (s, banner) = connectAndAuthenticate()

        // 握手/认证用阻塞模式完成；进入交互阶段切非阻塞：
        // 读循环轮询能响应 close()，也能在同一线程定期发 keepalive
        // （libssh2 会话非线程安全，keepalive 必须在读线程里发）。
        libssh2_session_set_blocking(s, 0)
        if (connection.keepAliveSeconds > 0) {
            libssh2_keepalive_config(s, 1, connection.keepAliveSeconds.toUInt())
        }

        val ch = openChannel(s)
            ?: run { cleanup(); throw SshException("打开会话通道失败") }
        channel = ch
        val term = connection.terminalType
        // cinterop 的 String 参数按 UTF-8 编码：长度必须传字节数（同 authenticate 的 username 约定）
        val termLen = term.encodeToByteArray().size
        if (retryUntilSuccess {
                libssh2_channel_request_pty_ex(ch, term, termLen.toUInt(), null, 0u, columns, rows, 0, 0)
            } != 0
        ) {
            cleanup()
            throw SshException("PTY 请求失败")
        }
        if (retryUntilSuccess { libssh2_channel_process_startup(ch, "shell", 5u, null, 0u) } != 0) {
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
        callbacks.onTraceStep("tcp")
        val s = libssh2_session_init_ex(null, null, null, null) ?: throw SshException("libssh2 初始化失败")
        session = s
        if (libssh2_session_handshake(s, sock) != 0) {
            throw SshException("SSH 握手失败: ${lastError(s)}")
        }
        callbacks.onTraceStep("kex")
        val banner = libssh2_session_banner_get(s)?.toKString() ?: ""

        val hostKey = computeHostKey(s)
        hostKeyInfo = hostKey
        if (!callbacks.verifyHostKey(hostKey)) {
            cleanup()
            throw SshException("主机密钥验证未通过")
        }
        if (!authenticate(s)) {
            cleanup()
            throw SshException("认证失败${authFailureReason ?: ""}")
        }
        callbacks.onTraceStep("auth")
        return s to banner
    }

    // ---------- 认证 ----------

    private fun authenticate(s: CPointer<LIBSSH2_SESSION>?): Boolean {
        // cinterop 的 String 参数按 UTF-8 编码，长度必须是字节数，否则非 ASCII 凭据必败
        val usernameBytes = connection.username.encodeToByteArray()
        var pubkeyError: String? = null
        connection.privateKeyPem?.let { pem ->
            val pemLen = pem.encodeToByteArray().size.toULong()
            var rc = libssh2_userauth_publickey_frommemory(
                s, connection.username, usernameBytes.size.toULong(),
                null, 0u, pem, pemLen, "",
            )
            // 加密私钥：提示用户输入口令后带 passphrase 重试
            if (rc != 0 && isEncryptedPem(pem)) {
                val passphrase = runBlocking {
                    callbacks.onPrompt(
                        AuthPrompt(
                            method = SshAuthMethod.PASSPHRASE,
                            name = "私钥口令",
                            instruction = "该私钥已加密（passphrase-protected），请输入口令。",
                            prompts = listOf(PromptField("Passphrase", echo = false)),
                        )
                    )
                }?.firstOrNull()
                if (!passphrase.isNullOrEmpty()) {
                    rc = libssh2_userauth_publickey_frommemory(
                        s, connection.username, usernameBytes.size.toULong(),
                        null, 0u, pem, pemLen, passphrase,
                    )
                }
            }
            if (rc == 0) return true
            pubkeyError = lastError(s)
        }
        connection.password?.let { pw ->
            if (libssh2_userauth_password_ex(
                    s, connection.username, usernameBytes.size.toUInt(),
                    pw, pw.encodeToByteArray().size.toUInt(), null,
                ) == 0
            ) {
                return true
            }
        }
        // KBI 兜底（含二次验证场景）。kbiHandler 为全局单例，加互斥避免并发会话串话。
        // 用完置空：staticCFunction 不能捕获变量导致全局持有 callbacks（→ controller
        // → 10k 行 buffer），不清空则会话关闭后一直无法回收，直到下一次 KBI 覆盖
        val kbiOk = runBlocking {
            kbiMutex.withLock {
                kbiHandler = callbacks::onPrompt
                try {
                    libssh2_userauth_keyboard_interactive_ex(
                        s, connection.username, usernameBytes.size.toUInt(), kbiCallback,
                    ) == 0
                } finally {
                    kbiHandler = null
                }
            }
        }
        if (kbiOk) return true
        authFailureReason = buildString {
            append("：")
            if (pubkeyError != null) {
                append("私钥认证失败（").append(pubkeyError).append("）")
                append("；若私钥带 passphrase，请确认口令是否正确，或改用密码认证")
            } else {
                append("密码与 keyboard-interactive 均未通过")
            }
        }
        return false
    }

    // ---------- 数据收发 ----------

    /** 非阻塞模式下重试直到非 EAGAIN，返回最后一次返回码（0=成功）。 */
    private fun retryUntilSuccess(block: () -> Int): Int {
        val deadline = Clock.System.now().toEpochMilliseconds() + 10_000
        var rc: Int
        while (true) {
            rc = block()
            if (rc != LIBSSH2_ERROR_EAGAIN) return rc
            if (closed || Clock.System.now().toEpochMilliseconds() >= deadline) return rc
            usleep(30_000u)
        }
    }

    /** 非阻塞模式下打开通道（EAGAIN 重试）。 */
    private fun openChannel(s: CPointer<LIBSSH2_SESSION>?): CPointer<LIBSSH2_CHANNEL>? {
        val deadline = Clock.System.now().toEpochMilliseconds() + 10_000
        while (!closed && Clock.System.now().toEpochMilliseconds() < deadline) {
            val ch = libssh2_channel_open_ex(s, "session", 7u, (2u * 1024u * 1024u), 32768u, null, 0u)
            if (ch != null) return ch
            usleep(30_000u)
        }
        return null
    }

    /** 到点发应用层 keepalive（必须在读线程调用，libssh2 会话非线程安全）。 */
    private fun maybeSendKeepalive(s: CPointer<LIBSSH2_SESSION>?) {
        if (connection.keepAliveSeconds <= 0) return
        val now = Clock.System.now().toEpochMilliseconds()
        if (now - lastKeepaliveMs >= connection.keepAliveSeconds * 1000L) {
            lastKeepaliveMs = now
            memScoped {
                val sec = alloc<IntVar>()
                libssh2_keepalive_send(s, sec.ptr)
            }
        }
    }

    private fun startReader(ch: CPointer<LIBSSH2_CHANNEL>?) {
        readerJob = scope.launch {
            try {
                val buf = ByteArray(64 * 1024)
                while (!closed) {
                    val n = buf.usePinned { pinned ->
                        libssh2_channel_read_ex(ch, 0, pinned.addressOf(0), buf.size.toULong())
                    }
                    when {
                        n > 0 -> callbacks.onOutput(buf.copyOf(n.toInt()))
                        n == 0L -> break
                        n.toInt() == LIBSSH2_ERROR_EAGAIN -> {
                            maybeSendKeepalive(session)
                            delay(30)
                        }
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
        // 远端断开也要释放 channel/session/socket，否则每次重连泄漏一个 fd + libssh2 对象。
        // 本函数在读线程（serialDispatcher）上执行，与读写无并发。
        cleanup()
        callbacks.onClosed(null)
    }

    override fun resize(columns: Int, rows: Int, widthPx: Int, heightPx: Int) {
        scope.launch {
            channel?.let { libssh2_channel_request_pty_size_ex(it, columns, rows, widthPx, heightPx) }
        }
    }

    // iOS 暂未实现 exec+pty 通道（libssh2 串行线程模型下需额外通道管理）；
    // 返回 null → 安装引导等调用方回退到 runCommand 路径。
    override fun startExec(command: String, columns: Int, rows: Int): SshExecChannel? = null

    override fun sendData(data: ByteArray) {
        if (closed || data.isEmpty()) return
        val copy = data.copyOf()
        // 编组到串行线程：调用方在 UI 线程，不能在此直接触碰 libssh2 会话
        scope.launch { writeAll(copy) }
    }

    /** 在 serialDispatcher 线程上执行的非阻塞完整写入。 */
    private fun writeAll(data: ByteArray) {
        val ch = channel ?: return
        // cinterop 把 write_ex 的 buf 映射为 String（按 UTF-8 编码 + 显式长度写入）。
        // 终端输入均为本端编码的合法 UTF-8，decode 是无损的。
        val text = data.decodeToString()
        var bytesDone = 0 // 已确认写出的字节数（按完整字符计）
        var charOffset = 0
        var idleRounds = 0
        while (bytesDone < data.size && !closed) {
            // length 参数 <= 剩余字符串实际字节数（bytesDone 只推进到字符边界）
            val rc = libssh2_channel_write_ex(
                ch, 0, text.substring(charOffset), (data.size - bytesDone).toULong(),
            )
            when {
                rc > 0 -> {
                    var n = rc.toInt()
                    // 只越过完整写出的字符：rc 可能停在 UTF-8 字符中间
                    while (charOffset < text.length) {
                        val c = text[charOffset]
                        val charLen = when {
                            c.code < 0x80 -> 1
                            c.code < 0x800 -> 2
                            c.isHighSurrogate() -> 4
                            else -> 3
                        }
                        if (charLen > n) break
                        n -= charLen
                        bytesDone += charLen
                        charOffset += if (c.isHighSurrogate()) 2 else 1
                    }
                    if (n > 0) {
                        // rc 落在字符中间：已写出的部分字节会随整字符重发而重复
                        // （String API 无法从字节中间续写，概率极低，容忍）
                        bytesDone += n
                    }
                    idleRounds = 0
                }
                rc.toInt() == LIBSSH2_ERROR_EAGAIN -> {
                    if (++idleRounds > 200) return // ~6s 写不出去，放弃
                    usleep(30_000u)
                }
                else -> return // 真错误（连接可能已断开）
            }
        }
    }

    override fun connectAndRun(command: String, timeoutMs: Long): CommandResult {
        // 非交互 exec 通道：用于 mosh-server 引导等一次性命令
        return try {
            val (s, _) = connectAndAuthenticate()
            libssh2_session_set_blocking(s, 0)
            val ch = openChannel(s)
                ?: throw SshException("打开 exec 通道失败")
            // 命令可能含非 ASCII（文件名等）：长度传字节数，否则 UTF-8 截断
            val cmdLen = command.encodeToByteArray().size
            fun startExec(): Int =
                libssh2_channel_process_startup(ch, "exec", 4u, command, cmdLen.toUInt())
            if (startExec().let {
                    var rc = it
                    var guard = 0
                    while (rc == LIBSSH2_ERROR_EAGAIN && guard++ < 300) { usleep(30_000u); rc = startExec() }
                    rc
                } != 0
            ) {
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
                    n.toInt() == LIBSSH2_ERROR_EAGAIN -> usleep(30_000u)
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

    override fun runCommandDetailed(command: String, timeoutMs: Long): CommandOutput? = runBlocking {
        // libssh2 会话非线程安全：读循环/keepalive 在串行线程运行，
        // 控制面命令必须编组到同一线程，避免并发触碰 LIBSSH2_SESSION。
        withContext(serialDispatcher) { doRunCommand(command, timeoutMs) }
    }

    /** 在已认证连接上打开 SFTP 通道（阻塞模式；调用方负责 shutdown + close）。 */
    fun openSftp(): CPointer<LIBSSH2_SFTP>? {
        val (s, _) = connectAndAuthenticate()
        libssh2_session_set_blocking(s, 1)
        // 阻塞模式 + keepalive_config：libssh2 自动发 keepalive（无需应用线程）
        if (connection.keepAliveSeconds > 0) {
            libssh2_keepalive_config(s, 1, connection.keepAliveSeconds.toUInt())
        }
        // 阻塞调用限时：断链/无响应 30s 返回错误 → 操作抛异常 → UI banner。
        //（不设则无限挂死：iOS SFTP 断线后 list/download 永久阻塞，与终端
        //  路径的 keepalive 检测相比，这是请求-响应式通道的兜底）
        libssh2_session_set_timeout(s, 30_000L)
        return libssh2_sftp_init(s)
    }

    /** 在已认证会话上开临时 exec 通道执行控制面命令，不重新认证、不打断交互 shell。 */
    private fun doRunCommand(command: String, timeoutMs: Long): CommandOutput? {
        val s = session ?: return null
        if (closed || channel == null) return null
        val ch = openChannel(s) ?: return null
        // 命令可能含非 ASCII：长度传字节数（UTF-8 截断会让远端拿到半个字符）
        val cmdLen = command.encodeToByteArray().size
        return try {
            if (retryUntilSuccess {
                    libssh2_channel_process_startup(ch, "exec", 4u, command, cmdLen.toUInt())
                } != 0
            ) {
                return null
            }
            val outChunks = ArrayList<ByteArray>()
            val errChunks = ArrayList<ByteArray>()
            val buf = ByteArray(16 * 1024)
            // 交替读 stdout(0)/stderr(1)：单流读满会让另一流阻塞通道。
            // 非阻塞模式下返回 0 = 该流 EOF；两流均 EOF 即命令输出结束。
            var outEof = false
            var errEof = false
            val deadline = Clock.System.now().toEpochMilliseconds() + timeoutMs
            while (Clock.System.now().toEpochMilliseconds() < deadline && !(outEof && errEof)) {
                var progressed = false
                if (!outEof) {
                    val n = buf.usePinned { pinned ->
                        libssh2_channel_read_ex(ch, 0, pinned.addressOf(0), buf.size.toULong())
                    }
                    when {
                        n > 0L -> { outChunks.add(buf.copyOf(n.toInt())); progressed = true }
                        n == 0L -> outEof = true
                        n.toInt() == LIBSSH2_ERROR_EAGAIN -> Unit
                        else -> break
                    }
                }
                if (!errEof) {
                    val n = buf.usePinned { pinned ->
                        libssh2_channel_read_ex(ch, 1, pinned.addressOf(0), buf.size.toULong())
                    }
                    when {
                        n > 0L -> { errChunks.add(buf.copyOf(n.toInt())); progressed = true }
                        n == 0L -> errEof = true
                        n.toInt() == LIBSSH2_ERROR_EAGAIN -> Unit
                        else -> break
                    }
                }
                if (!progressed) usleep(30_000u)
            }
            CommandOutput(
                stdout = outChunks.joinToString("") { it.decodeToString() },
                stderr = errChunks.joinToString("") { it.decodeToString() },
                exitCode = libssh2_channel_get_exit_status(ch),
            )
        } catch (_: Exception) {
            null
        } finally {
            try {
                libssh2_channel_close(ch)
                libssh2_channel_free(ch)
            } catch (_: Exception) {
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        // 不在调用线程（通常是 UI 主线程）阻塞等待：cleanup 里 channel_close 在死链路上
        // 可能 EAGAIN 循环数秒。异步编组到串行线程，先等读循环退出再释放资源。
        scope.launch {
            withTimeoutOrNull(2000) { readerJob?.join() }
            cleanup()
        }
        callbacks.onClosed(null)
    }

    override fun isActive(): Boolean = !closed

    // ---------- 工具 ----------

    private fun cleanup() {
        channel?.let {
            retryUntilSuccess { libssh2_channel_close(it) }
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
        // libssh2_session_hostkey 返回的 blob 本身已含算法字符串前缀
        // （与 libssh2_hostkey_hash(SHA256) 一致，即 OpenSSH 指纹口径），
        // 直接对整个 blob 做 SHA256，不要再手工拼一次类型前缀
        HostKeyInfo(typeName, "SHA256:" + base64Encode(Sha256.digest(keyBytes)).trimEnd('='), "")
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
        var flags = 0
        // 连接超时：与 sshj 的 setConnectTimeout 对齐（黑洞地址/防火墙丢包时快速失败，
        // 而不是依赖内核 TCP 重试（macOS/iOS 上可达 60s+，UI 表现为一直转圈））
        val timeoutMs = connection.connectTimeoutMillis.toInt()
        while (cur != null) {
            val s = socket(cur.pointed.ai_family, cur.pointed.ai_socktype, cur.pointed.ai_protocol)
            if (s < 0) {
                lastErr = errno
                cur = cur.pointed.ai_next
                continue
            }
            // 非阻塞 connect：立即返回 EINPROGRESS，用 poll 等待可写 + 超时
            flags = fcntl(s, F_GETFL, 0)
            fcntl(s, F_SETFL, flags or O_NONBLOCK)
            val rc = connect(s, cur.pointed.ai_addr, cur.pointed.ai_addrlen)
            if (rc == 0) {
                fd = s // 立即连上（回环/本机场景）
                break
            }
            if (errno != EINPROGRESS) {
                lastErr = errno
                close(s)
                cur = cur.pointed.ai_next
                continue
            }
            val pfd = alloc<pollfd>()
            pfd.fd = s
            pfd.events = POLLOUT.toShort()
            val prc = poll(pfd.ptr, 1u, timeoutMs)
            if (prc > 0 && (pfd.revents.toInt() and (POLLOUT or POLLERR or POLLHUP)) != 0) {
                // poll 就绪后二次 connect 判定结果：0 或 EISCONN 即连接成功
                // （POSIX 标准技巧，避免 getsockopt SO_ERROR 的跨平台类型负担）
                val rc2 = connect(s, cur.pointed.ai_addr, cur.pointed.ai_addrlen)
                if (rc2 == 0 || errno == EISCONN) {
                    fd = s
                    break
                }
                lastErr = errno
            } else if (prc == 0) {
                lastErr = ETIMEDOUT
            } else {
                lastErr = errno
            }
            close(s)
            cur = cur.pointed.ai_next
        }
        freeaddrinfo(res.value)
        if (fd < 0) {
            val msg = if (lastErr == ETIMEDOUT) {
                "连接超时（${timeoutMs / 1000}s 无响应）"
            } else {
                "无法连接 $host:$port (errno $lastErr)"
            }
            throw SshException(msg)
        }
        // 恢复阻塞模式：libssh2 后续读写依赖阻塞 socket
        fcntl(fd, F_SETFL, flags)
        fd
    }
}

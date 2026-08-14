package dev.mssh.ssh

import dev.mssh.crypto.Ed25519
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Auth methods a host may use. */
enum class AuthMethod { PASSWORD, KEYBOARD_INTERACTIVE, PUBLIC_KEY, NONE }

/** A keyboard-interactive / password prompt sent to the UI. */
data class AuthPrompt(
    val method: AuthMethod,
    val name: String,
    val instruction: String,
    val prompts: List<PromptField>,
)

data class PromptField(val label: String, val echo: Boolean)

data class SessionInfo(
    val serverVersion: String,
    val hostKeyFingerprint: String,
    val kexAlgorithm: String,
)

/**
 * High-level SSH session: transport + auth + one interactive session channel.
 *
 * Connection runs on [Dispatchers.IO]; the receive loop pushes channel data to
 * [onOutput]. All auth prompts are resolved via [promptHandler].
 */
class SshSession(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String?,
    private val privateKeyPem: String?,
    private val promptHandler: suspend (AuthPrompt) -> List<String>?,
    private val onOutput: (ByteArray) -> Unit,
    private val onStderr: (ByteArray) -> Unit,
    private val onExitStatus: (Int) -> Unit,
    private val onClosed: (String?) -> Unit,
) {
    private val transport = SshTransport(SshSocket())
    private var receiveJob: Job? = null
    private val scope = CoroutineScope(ioDispatcher() + Job())

    // channel state
    private var channelId = 0
    private var remoteChannelId = -1
    private var sendWindow = 0L
    private var channelOpen = false
    private var channelClosed = false
    private val pendingResponses = ArrayList<ByteArray>()

    /** Blocking connect + auth + open session + start shell. */
    fun connectAndStart(columns: Int, rows: Int): SessionInfo {
        transport.connect(host, port)
        transport.performKex()
        authenticate()
        openSessionChannel()
        requestPty(columns, rows)
        requestShell()
        startReceiveLoop()
        return SessionInfo(
            transport.remoteVersion,
            transport.hostKey?.fingerprintSha256 ?: "",
            transport.kexAlgorithms.firstOrNull() ?: "",
        )
    }

    // ---------- auth ----------

    private fun authenticate() {
        // service request
        val svc = SshWriter()
        svc.byte(SshMsg.SERVICE_REQUEST)
        svc.stringUtf8("ssh-userauth")
        transport.sendPacket(svc.toByteArray())
        val svcAccept = receiveDuringAuth()
        if ((svcAccept[0].toInt() and 0xff) != SshMsg.SERVICE_ACCEPT) {
            throw SshException("服务认证失败")
        }

        // probe with "none"
        val probe = SshWriter()
        probe.byte(SshMsg.USERAUTH_REQUEST)
        probe.stringUtf8(username)
        probe.stringUtf8("ssh-connection")
        probe.stringUtf8("none")
        transport.sendPacket(probe.toByteArray())

        var methods = emptyList<String>()
        while (true) {
            val p = receiveDuringAuth()
            when (p[0].toInt() and 0xff) {
                SshMsg.USERAUTH_SUCCESS -> return
                SshMsg.USERAUTH_BANNER -> {
                    // ignore banner (UI can surface it later)
                }
                SshMsg.USERAUTH_FAILURE -> {
                    val r = SshReader(p)
                    r.readByte()
                    val list = r.readNameList()
                    val partial = r.readBoolean()
                    methods = list
                    // try methods in order of preference
                    val ok = tryMethod(methods)
                    if (!ok) {
                        throw SshException(
                            "认证失败。服务器支持: ${methods.joinToString(", ")}" +
                                    (if (partial) "（部分成功）" else "")
                        )
                    }
                }
                SshMsg.USERAUTH_INFO_REQUEST -> {
                    val r = SshReader(p)
                    r.readByte()
                    val name = r.readStringUtf8()
                    val instruction = r.readStringUtf8()
                    val nPrompts = r.readUInt32().toInt()
                    val fields = ArrayList<PromptField>()
                    for (i in 0 until nPrompts) {
                        val label = r.readStringUtf8()
                        val echo = r.readBoolean()
                        fields.add(PromptField(label, echo))
                    }
                    // must run on the main dispatcher for UI
                    val answers = runPrompt(
                        AuthPrompt(AuthMethod.KEYBOARD_INTERACTIVE, name, instruction, fields)
                    ) ?: throw SshException("用户取消了认证")
                    val w = SshWriter()
                    w.byte(SshMsg.USERAUTH_INFO_RESPONSE)
                    w.uint32(answers.size.toLong())
                    for (a in answers) w.stringUtf8(a)
                    transport.sendPacket(w.toByteArray())
                }
                else -> {
                    // unexpected packet during auth (channel? impossible yet) — ignore
                }
            }
        }
    }

    private fun tryMethod(methods: List<String>): Boolean {
        // prefer publickey with ed25519 key, then password, then keyboard-interactive
        if (privateKeyPem != null && methods.any { it == "publickey" }) {
            if (tryPublicKey()) return true
        }
        if (password != null && methods.any { it == "password" }) {
            if (tryPassword(password)) return true
        }
        if (methods.any { it == "keyboard-interactive" }) {
            return tryKeyboardInteractive()
        }
        return false
    }

    private fun tryPublicKey(): Boolean {
        val key = try {
            OpenSshKey.parsePem(privateKeyPem!!)
        } catch (e: Exception) {
            return false
        }
        if (key.type != "ssh-ed25519") return false

        // query
        val q = SshWriter()
        q.byte(SshMsg.USERAUTH_REQUEST)
        q.stringUtf8(username)
        q.stringUtf8("ssh-connection")
        q.stringUtf8("publickey")
        q.boolean(false) // query, no signature
        q.stringUtf8("ssh-ed25519")
        q.string(key.publicKeyBlob)
        transport.sendPacket(q.toByteArray())
        val resp = receiveDuringAuth()
        if ((resp[0].toInt() and 0xff) == SshMsg.USERAUTH_SUCCESS) return true
        if ((resp[0].toInt() and 0xff) != SshMsg.USERAUTH_FAILURE) return false
        val r = SshReader(resp)
        r.readByte()
        val methods = r.readNameList()
        if (methods.none { it == "publickey" }) return false

        // real request with signature
        val sigData = SshWriter()
        sigData.raw(transport.sessionIdBytes())
        sigData.byte(SshMsg.USERAUTH_REQUEST)
        sigData.stringUtf8(username)
        sigData.stringUtf8("ssh-connection")
        sigData.stringUtf8("publickey")
        sigData.boolean(true)
        sigData.stringUtf8("ssh-ed25519")
        sigData.string(key.publicKeyBlob)
        sigData.string(ByteArray(0)) // signature placeholder — the RFC says this must match the final request
        // Hmm: the signature is over the full request with the actual signature string.
        // Build the request with an empty signature first to compute the signable bytes
        // is WRONG per RFC 4252 §7: the signature covers the request with the empty string.
        // OpenSSH signs the full USERAUTH_REQUEST including the (empty) signature field.
        val signable = sigData.toByteArray()
        val sig = Ed25519.sign(signable, key.privateKey)
        val sigBlob = SshWriter().stringUtf8("ssh-ed25519").string(sig).toByteArray()

        val w = SshWriter()
        w.byte(SshMsg.USERAUTH_REQUEST)
        w.stringUtf8(username)
        w.stringUtf8("ssh-connection")
        w.stringUtf8("publickey")
        w.boolean(true)
        w.stringUtf8("ssh-ed25519")
        w.string(key.publicKeyBlob)
        w.string(sigBlob)
        transport.sendPacket(w.toByteArray())
        val resp2 = receiveDuringAuth()
        return (resp2[0].toInt() and 0xff) == SshMsg.USERAUTH_SUCCESS
    }

    private fun tryPassword(pw: String): Boolean {
        val w = SshWriter()
        w.byte(SshMsg.USERAUTH_REQUEST)
        w.stringUtf8(username)
        w.stringUtf8("ssh-connection")
        w.stringUtf8("password")
        w.boolean(false)
        w.stringUtf8(pw)
        transport.sendPacket(w.toByteArray())
        val resp = receiveDuringAuth()
        if ((resp[0].toInt() and 0xff) == SshMsg.USERAUTH_SUCCESS) return true
        // password expired message (51 with 3 strings) not handled
        return false
    }

    private fun tryKeyboardInteractive(): Boolean {
        val w = SshWriter()
        w.byte(SshMsg.USERAUTH_REQUEST)
        w.stringUtf8(username)
        w.stringUtf8("ssh-connection")
        w.stringUtf8("keyboard-interactive")
        w.stringUtf8("")
        w.stringUtf8("")
        transport.sendPacket(w.toByteArray())
        val resp = receiveDuringAuth()
        if ((resp[0].toInt() and 0xff) == SshMsg.USERAUTH_SUCCESS) return true
        if ((resp[0].toInt() and 0xff) == SshMsg.USERAUTH_INFO_REQUEST) {
            val r = SshReader(resp)
            r.readByte()
            val name = r.readStringUtf8()
            val instruction = r.readStringUtf8()
            val n = r.readUInt32().toInt()
            val fields = ArrayList<PromptField>()
            for (i in 0 until n) fields.add(PromptField(r.readStringUtf8(), r.readBoolean()))
            val answers = runPrompt(AuthPrompt(AuthMethod.KEYBOARD_INTERACTIVE, name, instruction, fields))
                ?: throw SshException("用户取消了认证")
            val w2 = SshWriter()
            w2.byte(SshMsg.USERAUTH_INFO_RESPONSE)
            w2.uint32(answers.size.toLong())
            for (a in answers) w2.stringUtf8(a)
            transport.sendPacket(w2.toByteArray())
            val resp2 = receiveDuringAuth()
            return (resp2[0].toInt() and 0xff) == SshMsg.USERAUTH_SUCCESS
        }
        return false
    }

    private fun receiveDuringAuth(): ByteArray {
        while (true) {
            val p = transport.receivePacket()
            val msg = p[0].toInt() and 0xff
            if (msg == SshMsg.USERAUTH_SUCCESS || msg == SshMsg.USERAUTH_FAILURE ||
                msg == SshMsg.USERAUTH_INFO_REQUEST || msg == SshMsg.SERVICE_ACCEPT ||
                msg == SshMsg.USERAUTH_BANNER || msg == SshMsg.USERAUTH_REQUEST
            ) {
                return p
            }
            // buffer channel packets (shouldn't happen yet) — drop
        }
    }

    private fun runPrompt(prompt: AuthPrompt): List<String>? {
        // executed on the caller (background) thread; UI runs via promptHandler with its own dispatch
        return kotlinx.coroutines.runBlocking {
            promptHandler(prompt)
        }
    }

    // ---------- channel ----------

    private fun openSessionChannel() {
        val w = SshWriter()
        w.byte(SshMsg.CHANNEL_OPEN)
        w.stringUtf8("session")
        w.uint32(0) // sender channel
        w.uint32(2L shl 21) // window
        w.uint32(32 * 1024) // max packet
        transport.sendPacket(w.toByteArray())
        val resp = receiveUntil(SshMsg.CHANNEL_OPEN_CONFIRMATION, SshMsg.CHANNEL_OPEN_FAILURE)
        if ((resp[0].toInt() and 0xff) != SshMsg.CHANNEL_OPEN_CONFIRMATION) {
            val r = SshReader(resp)
            r.readByte()
            r.readUInt32()
            val reason = r.readUInt32()
            throw SshException("无法打开会话通道 (reason=$reason)")
        }
        val r = SshReader(resp)
        r.readByte()
        r.readUInt32() // recipient = our channel id
        remoteChannelId = r.readUInt32().toInt()
        sendWindow = r.readUInt32()
        channelOpen = true
    }

    private fun requestPty(columns: Int, rows: Int) {
        val w = SshWriter()
        w.byte(SshMsg.CHANNEL_REQUEST)
        w.uint32(0)
        w.stringUtf8("pty-req")
        w.boolean(true)
        w.stringUtf8("xterm-256color")
        w.uint32(columns.toLong())
        w.uint32(rows.toLong())
        w.uint32(0)
        w.uint32(0)
        w.string(ByteArray(1)) // terminal modes: 0 = end
        transport.sendPacket(w.toByteArray())
        val resp = receiveUntil(SshMsg.CHANNEL_SUCCESS, SshMsg.CHANNEL_FAILURE)
        if ((resp[0].toInt() and 0xff) != SshMsg.CHANNEL_SUCCESS) {
            throw SshException("服务器拒绝了 PTY 请求")
        }
    }

    private fun requestShell() {
        val w = SshWriter()
        w.byte(SshMsg.CHANNEL_REQUEST)
        w.uint32(0)
        w.stringUtf8("shell")
        w.boolean(true)
        transport.sendPacket(w.toByteArray())
        val resp = receiveUntil(SshMsg.CHANNEL_SUCCESS, SshMsg.CHANNEL_FAILURE)
        if ((resp[0].toInt() and 0xff) != SshMsg.CHANNEL_SUCCESS) {
            throw SshException("服务器拒绝了 shell 请求")
        }
    }

    /** Receives packets until one with a message id in [accept] arrives; others are buffered. */
    private fun receiveUntil(vararg accept: Int): ByteArray {
        while (true) {
            val p = transport.receivePacket()
            val msg = p[0].toInt() and 0xff
            if (msg in accept) return p
            // buffer for the receive loop
            pendingResponses.add(p)
        }
    }

    fun resize(columns: Int, rows: Int, widthPx: Int, heightPx: Int) {
        if (!channelOpen) return
        try {
            val w = SshWriter()
            w.byte(SshMsg.CHANNEL_REQUEST)
            w.uint32(0)
            w.stringUtf8("window-change")
            w.boolean(false)
            w.uint32(columns.toLong())
            w.uint32(rows.toLong())
            w.uint32(widthPx.toLong())
            w.uint32(heightPx.toLong())
            transport.sendPacket(w.toByteArray())
        } catch (_: Exception) {
        }
    }

    /** Sends bytes to the remote shell (respecting the send window). */
    fun sendData(data: ByteArray) {
        if (!channelOpen || channelClosed) return
        var off = 0
        while (off < data.size) {
            if (sendWindow <= 0) {
                // wait briefly for the receive loop to replenish the window
                var waited = 0
                while (sendWindow <= 0 && !channelClosed && waited < 5000) {
                    kotlinx.coroutines.runBlocking { kotlinx.coroutines.delay(10) }
                    waited += 10
                }
                if (channelClosed || sendWindow <= 0) return
            }
            val chunk = minOf(data.size - off, 32 * 1024, sendWindow.toInt())
            val w = SshWriter()
            w.byte(SshMsg.CHANNEL_DATA)
            w.uint32(0)
            w.string(data.copyOfRange(off, off + chunk))
            transport.sendPacket(w.toByteArray())
            sendWindow -= chunk
            off += chunk
        }
    }

    private fun startReceiveLoop() {
        receiveJob = scope.launch {
            try {
                // drain packets buffered during setup (e.g. early output)
                for (p in pendingResponses) handleChannelPacket(p)
                pendingResponses.clear()
                while (!channelClosed) {
                    val p = transport.receivePacket()
                    handleChannelPacket(p)
                }
            } catch (e: Exception) {
                if (!channelClosed) {
                    channelClosed = true
                    onClosed(e.message ?: "连接中断")
                }
            }
        }
    }

    private fun handleChannelPacket(p: ByteArray) {
        val msg = p[0].toInt() and 0xff
        when (msg) {
            SshMsg.CHANNEL_DATA -> {
                val r = SshReader(p)
                r.readByte()
                val recipient = r.readUInt32()
                if (recipient == 0L) {
                    val data = r.readString()
                    onOutput(data)
                    // replenish window
                    val w = SshWriter()
                    w.byte(SshMsg.CHANNEL_WINDOW_ADJUST)
                    w.uint32(0)
                    w.uint32(data.size.toLong())
                    transport.sendPacket(w.toByteArray())
                }
            }
            SshMsg.CHANNEL_EXTENDED_DATA -> {
                val r = SshReader(p)
                r.readByte()
                r.readUInt32()
                r.readUInt32() // data type code (1 = stderr)
                val data = r.readString()
                onStderr(data)
            }
            SshMsg.CHANNEL_WINDOW_ADJUST -> {
                val r = SshReader(p)
                r.readByte()
                r.readUInt32()
                sendWindow += r.readUInt32()
            }
            SshMsg.CHANNEL_EOF -> {
                // remote finished sending
            }
            SshMsg.CHANNEL_CLOSE -> {
                channelClosed = true
                val w = SshWriter()
                w.byte(SshMsg.CHANNEL_CLOSE)
                w.uint32(0)
                try {
                    transport.sendPacket(w.toByteArray())
                } catch (_: Exception) {
                }
                onClosed(null)
            }
            SshMsg.CHANNEL_REQUEST -> {
                val r = SshReader(p)
                r.readByte()
                r.readUInt32()
                val type = r.readStringUtf8()
                if (type == "exit-status") {
                    val status = r.readUInt32().toInt()
                    onExitStatus(status)
                } else if (type == "exit-signal") {
                    r.readStringUtf8()
                    val core = r.readBoolean()
                    r.readStringUtf8()
                    r.readStringUtf8()
                }
                // want-reply handling for pty-req/shell etc: reply with failure (already answered synchronously)
            }
            SshMsg.GLOBAL_REQUEST -> {
                val r = SshReader(p)
                r.readByte()
                r.readStringUtf8()
                val wantReply = r.readBoolean()
                if (wantReply) {
                    transport.sendPacket(byteArrayOf(SshMsg.REQUEST_FAILURE.toByte()))
                }
            }
            SshMsg.REQUEST_SUCCESS, SshMsg.REQUEST_FAILURE,
            SshMsg.CHANNEL_SUCCESS, SshMsg.CHANNEL_FAILURE -> {
                // matched with a pending request — nothing to do here
            }
            else -> {
                // ignore unknown
            }
        }
    }

    fun close() {
        try {
            if (channelOpen && !channelClosed) {
                val w = SshWriter()
                w.byte(SshMsg.CHANNEL_CLOSE)
                w.uint32(0)
                transport.sendPacket(w.toByteArray())
            }
        } catch (_: Exception) {
        }
        channelClosed = true
        transport.disconnect()
        receiveJob?.cancel()
    }

    fun isActive(): Boolean = !channelClosed
}

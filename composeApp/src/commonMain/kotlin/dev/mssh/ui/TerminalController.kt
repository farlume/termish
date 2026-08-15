package dev.mssh.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.mssh.data.Host
import dev.mssh.data.HostRepository
import dev.mssh.ssh.AuthPrompt
import dev.mssh.ssh.HostKeyInfo
import dev.mssh.ssh.SshCallbacks
import dev.mssh.ssh.SshConnection
import dev.mssh.ssh.SshSession
import dev.mssh.ssh.createSshSession
import dev.mssh.term.TerminalBuffer
import dev.mssh.term.TerminalEmulator
import dev.mssh.term.TerminalSelection
import dev.mssh.util.ioDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

enum class ConnStatus { IDLE, CONNECTING, AUTH, CONNECTED, CLOSED, ERROR }

data class AuthPromptRequest(val prompt: AuthPrompt) {
    internal val deferred = CompletableDeferred<List<String>?>()
}

data class HostKeyRequest(val key: HostKeyInfo) {
    internal val deferred = CompletableDeferred<Boolean>()
}

/**
 * 终端会话控制器：把 [SshSession] 的输出喂给 [TerminalEmulator]，
 * 把键盘输入发给远端，并把认证/主机密钥确认桥接到 Compose UI。
 */
class TerminalController(
    private val host: Host,
    private val password: String?,
    private val privateKeyPem: String?,
    private val repository: HostRepository,
    /** 意外断线时自动重连（指数退避，最多 3 次）。 */
    private val autoReconnect: Boolean = true,
) {
    val buffer = TerminalBuffer(80, 24, maxScrollbackLines = 10_000)
    val emulator = TerminalEmulator(buffer)
    val selection = TerminalSelection(buffer)

    var status by mutableStateOf(ConnStatus.IDLE)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var title by mutableStateOf(host.name)
        private set
    var authPrompt by mutableStateOf<AuthPromptRequest?>(null)
        private set
    var hostKeyPrompt by mutableStateOf<HostKeyRequest?>(null)
        private set
    var exitStatus by mutableStateOf<Int?>(null)
        private set

    /** 变更序号：UI 据此判断是否需要重绘。 */
    var frame by mutableStateOf(0L)
        private set

    /** OSC 52：远端程序写剪贴板时回调（由 UI 层接入系统剪贴板）。 */
    var onRemoteClipboard: ((String) -> Unit)? = null

    private var session: SshSession? = null
    private val scope = CoroutineScope(ioDispatcher() + SupervisorJob())
    private var lastCols = 80
    private var lastRows = 24
    private var reconnectAttempts = 0
    private var keepAliveActive = false

    init {
        emulator.onTitleChange = { t -> title = t }
        emulator.onResponse = { bytes -> session?.sendData(bytes) }
        emulator.onClipboardWrite = { text -> onRemoteClipboard?.invoke(text) }
    }

    fun connect(columns: Int, rows: Int) {
        if (status != ConnStatus.IDLE) return
        lastCols = columns
        lastRows = rows
        reconnectAttempts = 0
        doConnect()
    }

    private fun doConnect() {
        status = ConnStatus.CONNECTING
        scope.launch {
            try {
                val s = createSshSession(
                    SshConnection(
                        host = host.hostname,
                        port = host.port,
                        username = host.username,
                        password = password,
                        privateKeyPem = privateKeyPem,
                    ),
                    callbacks(),
                )
                session = s
                val info = s.connectAndStart(lastCols, lastRows)
                // TOFU：记录主机指纹
                info.hostKey?.let { repository.touchConnected(host.id, it.fingerprintSha256) }
                status = ConnStatus.CONNECTED
                reconnectAttempts = 0
                startKeepAlive()
                frame++
            } catch (e: Exception) {
                if (status != ConnStatus.CLOSED) {
                    status = ConnStatus.ERROR
                    errorMessage = e.message ?: "连接失败"
                }
            }
        }
    }

    private fun startKeepAlive() {
        if (!keepAliveActive) {
            keepAliveActive = true
            dev.mssh.util.SessionKeepAlive.onSessionStart()
        }
    }

    private fun stopKeepAlive() {
        if (keepAliveActive) {
            keepAliveActive = false
            dev.mssh.util.SessionKeepAlive.onSessionEnd()
        }
    }

    private fun callbacks() = object : SshCallbacks {
        override fun onOutput(data: ByteArray) {
            emulator.write(data)
            frame++
        }

        override fun onStderr(data: ByteArray) {
            emulator.write(data)
            frame++
        }

        override fun onExitStatus(status: Int) {
            exitStatus = status
        }

        override fun onClosed(reason: String?) {
            if (status == ConnStatus.CLOSED) return // 用户主动断开
            // 意外断线：指数退避自动重连，终端缓冲保留
            val wasConnected = status == ConnStatus.CONNECTED || status == ConnStatus.AUTH
            if (autoReconnect && wasConnected && reconnectAttempts < 3) {
                reconnectAttempts++
                session = null
                status = ConnStatus.CONNECTING
                errorMessage = "连接中断，正在重连（第 $reconnectAttempts 次）…"
                scope.launch {
                    kotlinx.coroutines.delay(2000L * reconnectAttempts)
                    if (status != ConnStatus.CLOSED) doConnect()
                }
                return
            }
            status = ConnStatus.CLOSED
            stopKeepAlive()
            if (reason != null) errorMessage = reason
        }

        override suspend fun onPrompt(prompt: AuthPrompt): List<String>? {
            val req = AuthPromptRequest(prompt)
            authPrompt = req
            return req.deferred.await()
        }

        override fun verifyHostKey(hostKey: HostKeyInfo): Boolean {
            // 已知指纹：严格比对
            val known = host.knownHostFingerprint
            if (known != null) {
                return known == hostKey.fingerprintSha256
            }
            // TOFU：首次连接由用户确认
            val req = HostKeyRequest(hostKey)
            hostKeyPrompt = req
            return runBlockingAwait(req.deferred)
        }
    }

    private fun runBlockingAwait(d: CompletableDeferred<Boolean>): Boolean =
        kotlinx.coroutines.runBlocking { d.await() }

    fun respondToPrompt(answers: List<String>?) {
        authPrompt?.let { req ->
            authPrompt = null
            req.deferred.complete(answers)
        }
    }

    fun respondToHostKey(accept: Boolean) {
        hostKeyPrompt?.let { req ->
            hostKeyPrompt = null
            req.deferred.complete(accept)
        }
    }

    fun sendText(text: String) {
        session?.sendData(text.encodeToByteArray())
    }

    fun quickCommands(): List<dev.mssh.data.QuickCommand> = host.quickCommands

    fun sendBytes(bytes: ByteArray) {
        session?.sendData(bytes)
    }

    fun resize(columns: Int, rows: Int, widthPx: Int, heightPx: Int) {
        if (columns <= 0 || rows <= 0) return
        lastCols = columns
        lastRows = rows
        if (columns != buffer.cols || rows != buffer.rows) {
            buffer.resize(columns, rows)
            frame++
        }
        session?.resize(columns, rows, widthPx, heightPx)
    }

    /** 光标闪烁：切换可见性并触发重绘。 */
    fun blinkCursor() {
        buffer.cursorVisible = !buffer.cursorVisible
        frame++
    }

    fun close() {
        status = ConnStatus.CLOSED
        stopKeepAlive()
        session?.close()
        session = null
    }

    fun isConnected(): Boolean = status == ConnStatus.CONNECTED || status == ConnStatus.AUTH
}

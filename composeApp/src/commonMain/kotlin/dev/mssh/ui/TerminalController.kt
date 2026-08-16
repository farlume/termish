package dev.mssh.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.mssh.data.Host
import dev.mssh.data.HostRepository
import dev.mssh.ssh.AuthPrompt
import dev.mssh.ssh.HostKeyInfo
import dev.mssh.ssh.MOSH_SERVER_BOOTSTRAP
import dev.mssh.ssh.MoshSession
import dev.mssh.ssh.SshException
import dev.mssh.ssh.SshCallbacks
import dev.mssh.ssh.SshConnection
import dev.mssh.ssh.SshSession
import dev.mssh.ssh.createMoshClient
import dev.mssh.ssh.createSshSession
import dev.mssh.ssh.parseMoshConnect
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

data class HostKeyRequest(
    val key: HostKeyInfo,
    /** 服务器指纹与已保存的不一致（服务器重装/换机/端口转发变化等），需用户重新核对。 */
    val changed: Boolean = false,
    val previousFingerprint: String? = null,
) {
    internal val deferred = CompletableDeferred<Boolean>()
}

/**
 * 终端会话控制器：把 [SshSession] 的输出喂给 [TerminalEmulator]，
 * 把键盘输入发给远端，并把认证/主机密钥确认桥接到 Compose UI。
 */
class TerminalController(
    val host: Host,
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
    /** 当前重连尝试次数：>0 且状态为 CONNECTING 时表示正在自动重连。 */
    var reconnectCount by mutableStateOf(0)
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

    /** 光标闪烁相位：纯渲染层动画状态，与协议显隐（buffer.cursorVisible）分离。 */
    var cursorBlinkPhase by mutableStateOf(true)
        private set

    /** OSC 52：远端程序写剪贴板时回调（由 UI 层接入系统剪贴板）。 */
    var onRemoteClipboard: ((String) -> Unit)? = null

    /** 本会话创建时的凭据签名：主机编辑后凭据变化即可据此判定旧会话过期。 */
    val credentialKey: String = credentialSignature(host, password, privateKeyPem)

    private var session: SshSession? = null
    private var moshSession: MoshSession? = null
    private val scope = CoroutineScope(ioDispatcher() + SupervisorJob())
    private var lastCols = 80
    private var lastRows = 24
    private var reconnectAttempts = 0
    private var keepAliveActive = false
    /** Mosh 主题注入：非空表示本会话开启（见 [prepareThemeSync]）。 */
    private var moshThemePayload: ByteArray? = null
    private var moshThemeInjected = false
    /** 已在远端输出里见到 herdr 标题（ESC]0;● …），表示远端可接收注入。 */
    private var moshThemeMarkerSeen = false

    init {
        emulator.onTitleChange = { t -> title = t }
        emulator.onResponse = { bytes -> session?.sendData(bytes) }
        emulator.onClipboardWrite = { text -> onRemoteClipboard?.invoke(text) }
    }

    fun connect(columns: Int, rows: Int) {
        // 允许 IDLE / 已断开 / 失败状态下（重）连接，缓冲保留
        if (status != ConnStatus.IDLE && status != ConnStatus.CLOSED && status != ConnStatus.ERROR) return
        lastCols = columns
        lastRows = rows
        reconnectAttempts = 0
        reconnectCount = 0
        errorMessage = null
        doConnect()
    }

    /** 按最近一次窗口尺寸重连（保留屏幕缓冲）；用于退到后台后回前台恢复会话。 */
    fun reconnect() {
        if (status != ConnStatus.IDLE && status != ConnStatus.CLOSED && status != ConnStatus.ERROR) return
        connect(lastCols, lastRows)
    }

    /** 是否允许自动重连（由打开会话时的设置决定）。 */
    val autoReconnectEnabled: Boolean get() = autoReconnect

    private fun doConnect() {
        status = ConnStatus.CONNECTING
        scope.launch {
            try {
                if (host.connectionMode == dev.mssh.data.ConnectionMode.MOSH) {
                    doConnectMosh()
                    return@launch
                }
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
                reconnectCount = 0
                errorMessage = null
                startKeepAlive()
                // 启动命令（如 tmux new -A -s main）：配合自动重连实现会话现场恢复
                if (host.startupCommand.isNotBlank()) {
                    s.sendData((host.startupCommand.trim() + "\n").encodeToByteArray())
                }
                frame++
            } catch (e: Exception) {
                if (status != ConnStatus.CLOSED) {
                    status = ConnStatus.ERROR
                    errorMessage = e.message
                }
            }
        }
    }

    /** Mosh 模式：先 SSH 引导 mosh-server，再拉起 mosh-client。 */
    private suspend fun doConnectMosh() {
        try {
            val callbacks = callbacks()
            val ssh = createSshSession(
                SshConnection(
                    host = host.hostname,
                    port = host.port,
                    username = host.username,
                    password = password,
                    privateKeyPem = privateKeyPem,
                ),
                callbacks,
            )
            val bootstrap = if (host.moshUdpPort in 1024..65535) {
                "mosh-server new -c 8 -p ${host.moshUdpPort} -l LANG=en_US.UTF-8"
            } else {
                MOSH_SERVER_BOOTSTRAP
            }
            val result = ssh.connectAndRun(bootstrap)
            result.hostKey?.let { repository.touchConnected(host.id, it.fingerprintSha256) }
            val (moshPort, moshKey) = parseMoshConnect(result.output)
                ?: throw SshException("mosh-server 引导失败：${result.output.trim().take(200)}")

            prepareThemeSync()
            val client = createMoshClient(
                ip = host.hostname,
                port = moshPort,
                key = moshKey,
                columns = lastCols,
                rows = lastRows,
                onOutput = { data ->
                    maybeInjectThemeOnOutput(data)
                    emulator.write(data)
                    frame++
                },
                onExit = {
                    if (status == ConnStatus.CONNECTED) {
                        status = ConnStatus.CLOSED
                        stopKeepAlive()
                    } else if (status == ConnStatus.CONNECTING) {
                        // mosh-client 在连接建立前就退出（如 UDP 端口不可达）：
                        // 不能继续显示 Connected，把真实原因留在画布（stderr 已合并），
                        // 状态置为 ERROR 而不是被后续代码覆盖成 CONNECTED。
                        status = ConnStatus.ERROR
                        errorMessage = "mosh 连接失败：客户端已退出（检查 UDP 端口/防火墙）"
                        stopKeepAlive()
                    }
                },
            )
            moshSession = client
            if (!client.isActive()) {
                status = ConnStatus.ERROR
                errorMessage = "mosh 连接失败：客户端已退出。若主机是域名或经 NAS/路由器端口转发，" +
                    "请确认 UDP 端口（自动 60000-61000，或在主机里固定一个端口）已转发且未被防火墙拦截。"
                stopKeepAlive()
                return@doConnectMosh
            }
            // herdr 的 ● 标记可能因 mosh 转发时序不稳定而检测不到，
            // 固定延迟兜底注入（字节会等握手完成后才送达远端 stdin）。
            if (moshThemePayload != null) {
                scope.launch {
                    kotlinx.coroutines.delay(2500)
                    injectThemeIfNeeded(requireMarker = false)
                }
            }
            status = ConnStatus.CONNECTED
            reconnectAttempts = 0
            reconnectCount = 0
            errorMessage = null
            startKeepAlive()
            // 启动命令同样适用于 mosh 会话（登录 shell 里执行）
            if (host.startupCommand.isNotBlank()) {
                client.sendData((host.startupCommand.trim() + "\n").encodeToByteArray())
            }
            frame++
        } catch (e: Throwable) {
            if (status != ConnStatus.CLOSED) {
                status = ConnStatus.ERROR
                errorMessage = e.message
            }
        }
    }

    /**
     * Mosh 下把手机终端主题注入远端（herdr 等从 stdin 解析 OSC 应答）。
     * 见 [dev.mssh.term.TerminalEmulator.buildThemeSyncPayload]。
     * 连接后固定延迟注入，并在输出里见到 herdr 的 ● 标记（tab 标签）时提前注入；
     * 字节通过 mosh 输入通道送达，herdr 会像收到终端应答一样解析。
     */
    private fun prepareThemeSync() {
        if (!host.moshThemeSync) return
        moshThemePayload = emulator.buildThemeSyncPayload()
        moshThemeInjected = false
        moshThemeMarkerSeen = false
    }

    /** 输出里出现 herdr 的 ● 标记（tab 标签/标题）时提前注入。 */
    private fun maybeInjectThemeOnOutput(data: ByteArray) {
        if (moshThemePayload == null || moshThemeInjected) return
        val text = data.decodeToString()
        if (text.contains("●")) {
            moshThemeMarkerSeen = true
            injectThemeIfNeeded(requireMarker = true)
        }
    }

    private fun injectThemeIfNeeded(requireMarker: Boolean = true) {
        val payload = moshThemePayload ?: return
        if (moshThemeInjected) return
        if (requireMarker && !moshThemeMarkerSeen) return
        val client = moshSession ?: return
        if (status == ConnStatus.CONNECTED && client.isActive()) {
            moshThemeInjected = true
            client.sendData(payload)
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
                reconnectCount = reconnectAttempts
                errorMessage = null
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
            // 优先读仓库里最新保存的指纹：连接成功后 touchConnected 只写了仓库，
            // 内存中的 Host（AppRoot hosts 状态/本控制器）不会刷新，直接读 host 的
            // 话同一进程内首次连接后每次重连都会看到 null 而重复弹信任窗。
            val known = repository.getHost(host.id)?.knownHostFingerprint
                ?: host.knownHostFingerprint
            if (known != null) {
                if (known == hostKey.fingerprintSha256) return true
                // 指纹已变更：不再硬失败（否则改地址/服务器换钥后永远连不上、且无重置入口），
                // 改为弹窗让用户核对新旧指纹后决定。
                val req = HostKeyRequest(hostKey, changed = true, previousFingerprint = known)
                hostKeyPrompt = req
                return runBlockingAwait(req.deferred)
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
        moshSession?.sendData(text.encodeToByteArray()) ?: session?.sendData(text.encodeToByteArray())
    }

    fun quickCommands(): List<dev.mssh.data.QuickCommand> = host.quickCommands

    fun sendBytes(bytes: ByteArray) {
        moshSession?.sendData(bytes) ?: session?.sendData(bytes)
    }

    fun resize(columns: Int, rows: Int, widthPx: Int, heightPx: Int) {
        if (columns <= 0 || rows <= 0) return
        lastCols = columns
        lastRows = rows
        if (columns != buffer.cols || rows != buffer.rows) {
            buffer.resize(columns, rows)
            frame++
        }
        moshSession?.resize(columns, rows) ?: session?.resize(columns, rows, widthPx, heightPx)
    }

    /** 光标闪烁：切换渲染相位并触发重绘；稳态样式（2/4/6）不闪。 */
    fun blinkCursor() {
        // DECSCUSR 稳态样式不闪烁，且不改变相位（绘制时稳态样式不读相位）
        if (buffer.cursorStyle == 2 || buffer.cursorStyle == 4 || buffer.cursorStyle == 6) return
        cursorBlinkPhase = !cursorBlinkPhase
        frame++
    }

    fun close() {
        status = ConnStatus.CLOSED
        stopKeepAlive()
        moshSession?.close()
        moshSession = null
        session?.close()
        session = null
    }

    fun isConnected(): Boolean = status == ConnStatus.CONNECTED || status == ConnStatus.AUTH
}

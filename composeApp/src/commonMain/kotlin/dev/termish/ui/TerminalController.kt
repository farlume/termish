package dev.termish.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.termish.data.Host
import dev.termish.data.HostRepository
import dev.termish.ssh.AuthPrompt
import dev.termish.ssh.HostKeyInfo
import dev.termish.ssh.MOSH_SERVER_BOOTSTRAP
import dev.termish.ssh.MoshSession
import dev.termish.ssh.SYSTEM_PROBE_COMMAND
import dev.termish.ssh.SshException
import dev.termish.ssh.SshCallbacks
import dev.termish.ssh.SshConnection
import dev.termish.ssh.SshSession
import dev.termish.ssh.createKmpMoshSession
import dev.termish.ssh.createSshSession
import dev.termish.ssh.detectSystemFromOutput
import dev.termish.ssh.parseMoshConnect
import dev.termish.term.TerminalBuffer
import dev.termish.term.TerminalEmulator
import dev.termish.term.TerminalSelection
import dev.termish.term.argbToRgb
import dev.termish.ui.theme.TerminalThemes
import dev.termish.util.ioDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

enum class ConnStatus { IDLE, CONNECTING, AUTH, CONNECTED, CLOSED, ERROR }

/** mosh 链路失联提示阈值（秒）：双方心跳约 3s，5s 避免单包丢失抖动。
 *  失联 banner 与状态点共用。 */
internal const val LINK_LOST_THRESHOLD_SECONDS = 5

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
    /** mosh 链路失联秒数（0=健康；达到阈值时 UI 显示「失去联系」banner，会话仍保持）。 */
    var linkLostSeconds by mutableStateOf(0)
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

    /** 自动探测到远端系统并已保存时回调（Termius 式识别；UI 据此刷新主机列表）。 */
    var onSystemDetected: ((Host) -> Unit)? = null

    /** 本会话创建时的凭据签名：主机编辑后凭据变化即可据此判定旧会话过期。 */
    val credentialKey: String = credentialSignature(host, password, privateKeyPem)

    private var session: SshSession? = null
    private var moshSession: MoshSession? = null
    private val scope = CoroutineScope(ioDispatcher() + SupervisorJob())
    private var lastCols = 80
    private var lastRows = 24
    /** 会话唯一标识（同主机多会话区分；Compose key() 重组用）。 */
    val sessionId: String = "${host.id}:${kotlin.random.Random.nextLong()}"
    private var reconnectAttempts = 0
    private var keepAliveActive = false
    /** 自动重连的延迟任务：close() 时取消，防止关闭后仍被延迟协程拉起。 */
    private var reconnectJob: kotlinx.coroutines.Job? = null
    /** Mosh 主题注入：非空表示本会话开启（见 [prepareThemeSync]）。 */
    private var moshThemePayload: ByteArray? = null
    private var moshThemeInjected = false
    init {
        // 终端默认前景/背景色：SSH 应答 OSC 10/11 与 Mosh 主题注入都依赖它。
        // 必须在创建时按当前主题设置，不能等 TerminalScreen 组合——后台自动重连、
        // 会话恢复等路径不经过界面，否则会拿深色初始值告诉远端"手机是深色主题"，
        // 导致白主题下 pane 渲染成黑色/深色蒙层。
        val terminalTheme = TerminalThemes.ALL
            .getOrElse(repository.loadSettings().terminalThemeIndex) { TerminalThemes.ALL[0] }
        buffer.defaultFgRgb = argbToRgb(terminalTheme.foreground)
        buffer.defaultBgRgb = argbToRgb(terminalTheme.background)
        buffer.defaultCursorRgb = argbToRgb(terminalTheme.cursor)

        emulator.onTitleChange = { t -> title = t }
        emulator.onResponse = { bytes -> session?.sendData(bytes) }
        emulator.onClipboardWrite = { text ->
            // OSC 52 远端写剪贴板受设置开关控制（远端可静默覆盖剪贴板，默认开）
            if (repository.loadSettings().osc52Clipboard) onRemoteClipboard?.invoke(text)
        }
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

    private fun newConnection() = SshConnection(
        host = host.hostname,
        port = host.port,
        username = host.username,
        password = password,
        privateKeyPem = privateKeyPem,
        keepAliveSeconds = repository.loadSettings().keepaliveSeconds,
    )

    private fun doConnect() {
        status = ConnStatus.CONNECTING
        scope.launch {
            try {
                if (host.connectionMode == dev.termish.data.ConnectionMode.MOSH) {
                    doConnectMosh()
                    return@launch
                }
                val s = createSshSession(newConnection(), callbacks())
                session = s
                val info = s.connectAndStart(lastCols, lastRows)
                // 连接期间用户可能已关闭会话：不能置 CONNECTED，且必须释放刚建好的连接
                if (status == ConnStatus.CLOSED) {
                    session = null
                    try { s.close() } catch (_: Exception) {
                    }
                    return@launch
                }
                // TOFU：记录主机指纹
                info.hostKey?.let { repository.touchConnected(host.id, it.fingerprintSha256) }
                status = ConnStatus.CONNECTED
                reconnectAttempts = 0
                reconnectCount = 0
                errorMessage = null
                startKeepAlive()
                // 自动探测远端系统（Termius 式）：system 未知时在已认证连接上
                // 后台 exec 一次，不重新认证、不阻塞交互；成功即保存并刷新列表。
                if (host.system.isBlank()) {
                    val ssh = s
                    scope.launch {
                        val raw = runCatching { ssh.probeSystem() }.getOrNull()
                        val detected = raw?.let { detectSystemFromOutput(it) }
                        if (detected != null && detected.isNotBlank() && status == ConnStatus.CONNECTED) {
                            val updated = host.copy(system = detected)
                            repository.upsertHost(updated)
                            onSystemDetected?.invoke(updated)
                        }
                    }
                }
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
            // 引导用的临时 SSH 会话：connectAndRun 的 finally 会 close() 并同步触发
            // onClosed；若路由到主回调，会把 CONNECTING 置为 CLOSED，导致下方
            // "status == CLOSED" 检查误判为用户关闭，刚拉起的 mosh-client 被立即销毁。
            // onPrompt / verifyHostKey 仍需委托主回调（认证与 TOFU 确认）。
            val bootstrapCallbacks = object : SshCallbacks by callbacks {
                override fun onClosed(reason: String?) {}
            }
            val ssh = createSshSession(newConnection(), bootstrapCallbacks)
            val baseBootstrap = if (host.moshUdpPort in 1024..65535) {
                "mosh-server new -c 256 -p ${host.moshUdpPort} -l LANG=en_US.UTF-8"
            } else {
                MOSH_SERVER_BOOTSTRAP
            }
            // 引导同时探测远端系统：探测输出跟在 MOSH CONNECT 之后，
            // 不影响 parseMoshConnect，自动识别系统（用户无需手填）。
            val bootstrap = "$baseBootstrap; $SYSTEM_PROBE_COMMAND"
            val result = ssh.connectAndRun(bootstrap)
            result.hostKey?.let { repository.touchConnected(host.id, it.fingerprintSha256) }
            val (moshPort, moshKey) = parseMoshConnect(result.output)
                ?: throw SshException("mosh-server 引导失败：${result.output.trim().take(200)}")
            detectSystemFromOutput(result.output)?.takeIf { it.isNotBlank() }?.let { detected ->
                if (host.system.isBlank()) {
                    val updated = host.copy(system = detected)
                    repository.upsertHost(updated)
                    onSystemDetected?.invoke(updated)
                }
            }

            prepareThemeSync()
            // 纯 Kotlin mosh 客户端：影子终端状态直接同步进 UI buffer，
            // 不经过字节流路径（emulator.write）。
            val client = createKmpMoshSession(
                ip = host.hostname,
                port = moshPort,
                key = moshKey,
                columns = lastCols,
                rows = lastRows,
                scope = scope,
                uiBuffer = buffer,
                onTitle = { t -> title = t },
                onClipboard = { text ->
                    if (repository.loadSettings().osc52Clipboard) onRemoteClipboard?.invoke(text)
                },
                onExit = { reason ->
                    if (status == ConnStatus.CONNECTED) {
                        status = ConnStatus.CLOSED
                        // 会话异常/超时等原因必须浮现（此前被静默丢弃，
                        // 用户只能看到「已断开」且不知为何）
                        if (reason != null) errorMessage = reason
                        stopKeepAlive()
                    } else if (status == ConnStatus.CONNECTING) {
                        status = ConnStatus.ERROR
                        errorMessage = reason ?: "mosh 连接失败：客户端已退出"
                        stopKeepAlive()
                    }
                },
                // 状态拷进 buffer 后显式触发重绘，否则新输出/预测回显要等
                // 光标闪烁等偶发 frame 变更（0~530ms）才上屏
                onFrame = { frame++ },
                // 收到对端首包才算真正连上（mosh still_connecting 语义）：
                // UDP 不通时状态停留在「连接中」，15s 超时由 onExit 报出原因
                onPeerConnected = { moshSession?.let { onMoshConnected(it) } },
                onLinkStatus = { secs -> linkLostSeconds = secs },
            )
            moshSession = client
            // 连接期间用户可能已关闭会话：不能置 CONNECTED，且必须拉起后立即销毁
            if (status == ConnStatus.CLOSED) {
                moshSession = null
                client.close()
                return@doConnectMosh
            }
            if (!client.isActive()) {
                status = ConnStatus.ERROR
                errorMessage = "mosh 连接失败：客户端已退出。若主机是域名或经 NAS/路由器端口转发，" +
                    "请确认 UDP 端口（自动 60000-61000，或在主机里固定一个端口）已转发且未被防火墙拦截。"
                stopKeepAlive()
                return@doConnectMosh
            }
            // 等 onPeerConnected 首包回调再置 CONNECTED（UDP 不通时保持「连接中」）
        } catch (e: Throwable) {
            if (status != ConnStatus.CLOSED) {
                status = ConnStatus.ERROR
                errorMessage = e.message
            }
        }
    }

    /** mosh 会话真正建立后的统一收尾（收到对端首包时回调）。 */
    private fun onMoshConnected(client: MoshSession) {
        if (status == ConnStatus.CLOSED) { // 等待首包期间用户已关闭
            client.close()
            return
        }
        // 延迟注入：不能太早（herdr 接管前字节会被 shell readline 当输入回显成乱码），
        // 也不能太晚（herdr 默认主题会显示几秒灰色蒙层）。1200ms 时 herdr 通常已接管。
        if (moshThemePayload != null) {
            scope.launch {
                kotlinx.coroutines.delay(1200)
                injectThemeIfNeeded()
            }
        }
        status = ConnStatus.CONNECTED
        reconnectAttempts = 0
        reconnectCount = 0
        errorMessage = null
        linkLostSeconds = 0
        startKeepAlive()
        // 启动命令同样适用于 mosh 会话（登录 shell 里执行）
        if (host.startupCommand.isNotBlank()) {
            client.sendData((host.startupCommand.trim() + "\n").encodeToByteArray())
        }
        frame++
    }

    /**
     * Mosh 下把手机终端主题注入远端（herdr 等从 stdin 解析 OSC 应答）。
     * 见 [dev.termish.term.TerminalEmulator.buildThemeSyncPayload]。
     * 连接后固定延迟注入；字节通过 mosh 输入通道送达，
     * herdr 会像收到终端应答一样解析。
     */
    private fun prepareThemeSync() {
        // 仅当配置了启动命令（TUI 会话：herdr/tmux 等）才注入：
        // 注入的 OSC 应答会作为「用户输入」送达远端 shell，普通 shell（bash
        // readline）不解析 OSC，会把 ESC]10;… 原样回显成特殊字符。
        // 有启动命令说明会话会跑 TUI（herdr 查询终端主题），注入才安全有效。
        if (!host.moshThemeSync || host.startupCommand.isBlank()) return
        moshThemePayload = emulator.buildThemeSyncPayload()
        moshThemeInjected = false
    }

    private fun injectThemeIfNeeded() {
        val payload = moshThemePayload ?: return
        if (moshThemeInjected) return
        val client = moshSession ?: return
        if (status == ConnStatus.CONNECTED && client.isActive()) {
            moshThemeInjected = true
            client.sendData(payload)
        }
    }

    private fun startKeepAlive() {
        if (!keepAliveActive) {
            keepAliveActive = true
            dev.termish.util.SessionKeepAlive.onSessionStart()
        }
    }

    private fun stopKeepAlive() {
        if (keepAliveActive) {
            keepAliveActive = false
            dev.termish.util.SessionKeepAlive.onSessionEnd()
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
                }.also { reconnectJob = it }
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
            // 首次连接：用户关闭了「首次连接确认」则直接信任（设置里仍可看到指纹）
            if (!repository.loadSettings().verifyHostKeyOnFirstUse) return true
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

    fun quickCommands(): List<dev.termish.data.QuickCommand> = host.quickCommands

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
        linkLostSeconds = 0
        // 取消挂起的自动重连，防止 close 后延迟任务又拉起连接
        reconnectJob?.cancel()
        reconnectJob = null
        // 完成挂起的认证/主机密钥弹窗，释放阻塞在 await 上的 SSH 线程
        authPrompt?.let { authPrompt = null; it.deferred.complete(null) }
        hostKeyPrompt?.let { hostKeyPrompt = null; it.deferred.complete(false) }
        stopKeepAlive()
        moshSession?.close()
        moshSession = null
        session?.close()
        session = null
    }

    fun isConnected(): Boolean = status == ConnStatus.CONNECTED || status == ConnStatus.AUTH
}

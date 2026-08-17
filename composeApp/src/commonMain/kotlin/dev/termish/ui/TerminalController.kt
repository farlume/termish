package dev.termish.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.termish.data.Host
import dev.termish.data.HostRepository
import dev.termish.ssh.AuthPrompt
import dev.termish.ssh.HostKeyInfo
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
import dev.termish.util.NetworkChangeKind
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

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
    /** SSH 会话工厂（测试注入 fake；生产默认走平台引擎）。 */
    internal val sessionFactory: (SshConnection, SshCallbacks) -> SshSession = ::createSshSession,
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

    companion object {
        /** SSH 意外断线自动重连上限次数（指数退避，见 [RECONNECT_BASE_DELAY_MS]）。 */
        private const val RECONNECT_SSH_MAX = 3
        /** mosh 已连接后异常退出自动重连上限（UDP 环境更脆弱，上限更低）。 */
        private const val RECONNECT_MOSH_MAX = 2
        /** 自动重连基础退避：第 n 次重连延迟 2n 秒。 */
        private const val RECONNECT_BASE_DELAY_MS = 2_000L
        /** 连接成功后的网络事件免疫期：刚连上不折腾，避免「连上即断」循环。 */
        private const val NETWORK_IMMUNE_MS = 30_000L
        /** 网络切换主动重连的防抖窗口。 */
        private const val NETWORK_DEBOUNCE_MS = 15_000L
        /** 连接保持稳定后重连计数归零的观察期（mosh「连上即退」防循环）。 */
        private const val MOSH_STABLE_RESET_MS = 30_000L
        /** mosh 主题注入延迟：太早会被 shell readline 当输入回显成乱码，太晚 herdr 显示灰蒙层。 */
        private const val MOSH_THEME_INJECT_DELAY_MS = 1_200L
        /** SSH 输出队列容量（chunk 数，单 chunk ≤64KB）：满时 reader 协程挂起施加
         *  TCP 背压，不丢字节也不无限撑爆内存（cat 大文件场景）。 */
        private const val OUTPUT_QUEUE_CAPACITY = 256
        /** 主线程批量消费单帧预算：抽干同帧到达的输出后一次性触发重绘，
         *  避免小包洪泛时每包一次 frame 抖动；到预算即让出主线程。 */
        private const val OUTPUT_BATCH_BUDGET_MS = 8
        /** 认证/主机密钥弹窗等待上限：页面销毁或用户长期不响应时按拒绝处理，
         *  防止连接线程永久阻塞（sshd 自身也有登录宽限，超时连接本就会被掐断）。 */
        private const val PROMPT_TIMEOUT_MS = 120_000L
    }

    private var session: SshSession? = null
    private var moshSession: MoshSession? = null
    private val scope = CoroutineScope(ioDispatcher() + SupervisorJob())
    /** destroy() 后置位：reader 协程停止向输出队列投递（队列已关闭，投递会抛）。 */
    @Volatile
    private var destroyed = false
    /**
     * SSH 字节输出队列。reader 协程只投递，主线程单点消费喂 emulator——
     * 由此 buffer 的全部读写（渲染 / resize / 选择 / 模拟器写入）都串行在主线程，
     * 消除 reader 写入与 Canvas 绘制之间的竞争（此前 resize 重建行数组中途遇到
     * reader 写入可能越界；mosh 路径本就是主线程拷贝同步，无此问题）。
     */
    private val outputQueue = Channel<ByteArray>(capacity = OUTPUT_QUEUE_CAPACITY)
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
    /** 单调钟：网络免疫期/防抖用单调毫秒，避免墙钟被用户改时间/NTP 校正干扰。 */
    private val mono = kotlin.time.TimeSource.Monotonic.markNow()
    private fun nowMs(): Long = mono.elapsedNow().inWholeMilliseconds
    /** 连接成功后的网络事件免疫截止（单调毫秒）：期内不触发主动重连（防刚连上即断）。 */
    private var networkImmuneUntilMs = 0L
    /** 最近一次主动重连时刻（单调毫秒）：15 秒防抖，避免网络抖动引发连续重连。 */
    private var lastNetworkReconnectAtMs = 0L
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
        // 输出消费循环：只在主线程动 buffer。批量抽干同帧到达的输出再统一重绘；
        // 持续洪泛时 receiveCatching 不挂起，必须显式 yield 让 Compose 有机会绘制。
        scope.launch(Dispatchers.Main) {
            while (true) {
                val first = outputQueue.receiveCatching().getOrNull() ?: break
                emulator.write(first)
                val mark = kotlin.time.TimeSource.Monotonic.markNow()
                while (mark.elapsedNow().inWholeMilliseconds < OUTPUT_BATCH_BUDGET_MS) {
                    val next = outputQueue.tryReceive().getOrNull() ?: break
                    emulator.write(next)
                }
                frame++
                yield()
            }
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
        terminalType = repository.loadSettings().terminalType,
    )

    private fun doConnect() {
        status = ConnStatus.CONNECTING
        scope.launch {
            try {
                if (host.connectionMode == dev.termish.data.ConnectionMode.MOSH) {
                    doConnectMosh()
                    return@launch
                }
                val s = sessionFactory(newConnection(), callbacks())
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
                // 网络切换免疫期：刚连上 30 秒内的网络事件不再触发主动重连
                networkImmuneUntilMs = nowMs() + NETWORK_IMMUNE_MS
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // 协程取消不是连接失败：不置 ERROR、不停保活
            } catch (e: Exception) {
                if (status != ConnStatus.CLOSED) {
                    status = ConnStatus.ERROR
                    errorMessage = e.message
                    // 自动重连失败：会话已死，必须停掉保活，否则前台服务+wakelock 空转
                    //（首连失败时 keepAliveActive=false，stopKeepAlive 有 guard，安全）
                    stopKeepAlive()
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
            // mosh -c 只支持 8/256 两档：xterm-256color → 256（默认），
            // 其余 TERM（vt100/linux 等）按 8 色保守协商
            val moshColors = if (repository.loadSettings().terminalType == "xterm-256color") "256" else "8"
            val baseBootstrap = if (host.moshUdpPort in 1024..65535) {
                "mosh-server new -c $moshColors -p ${host.moshUdpPort} -l LANG=en_US.UTF-8"
            } else {
                "mosh-server new -c $moshColors -l LANG=en_US.UTF-8"
            }
            // 引导同时探测远端系统：探测输出跟在 MOSH CONNECT 之后，
            // 不影响 parseMoshConnect，自动识别系统（用户无需手填）。
            val bootstrap = "$baseBootstrap; $SYSTEM_PROBE_COMMAND"
            val result = ssh.connectAndRun(bootstrap)
            result.hostKey?.let { repository.touchConnected(host.id, it.fingerprintSha256) }
            val (moshPort, moshKey) = parseMoshConnect(result.output)
                ?: throw SshException(
                    if (host.moshUdpPort in 1024..65535 && result.output.contains("Address already in use")) {
                        "固定 UDP 端口 ${host.moshUdpPort} 仍被旧会话占用（刚切换网络，稍等几秒重试即可）"
                    } else {
                        "mosh-server 引导失败：${result.output.trim().take(200)}"
                    },
                )
            detectSystemFromOutput(result.output)?.takeIf { it.isNotBlank() }?.let { detected ->
                if (host.system.isBlank()) {
                    val updated = host.copy(system = detected)
                    repository.upsertHost(updated)
                    onSystemDetected?.invoke(updated)
                }
            }

            // payload 读 buffer 默认色（buildThemeSyncPayload），而 buffer 只在
            // 主线程读写（TerminalScreen 换主题时也会更新默认色）：构建必须切到
            // 主线程，不能在这个 IO 协程里读
            withContext(Dispatchers.Main) { prepareThemeSync() }
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
                onExit = { reason -> handleMoshExit(reason) },
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (status != ConnStatus.CLOSED) {
                status = ConnStatus.ERROR
                errorMessage = e.message
                // 重连失败：停保活防空转（见 doConnect 的 catch 注释）
                stopKeepAlive()
            }
        }
    }

    /** mosh 客户端退出回调：已连接后异常退出走自动重连（上限 [RECONNECT_MOSH_MAX]），
     *  连接中退出报连接失败。用户主动 close 时状态为 CLOSED，直接忽略。 */
    private fun handleMoshExit(reason: String?) {
        if (status == ConnStatus.CONNECTED) {
            moshSession = null
            // 已连接后异常退出（非用户关闭）：自动重连
            if (autoReconnect && reconnectAttempts < RECONNECT_MOSH_MAX) {
                reconnectAttempts++
                status = ConnStatus.CONNECTING
                reconnectCount = reconnectAttempts
                errorMessage = null
                scope.launch {
                    kotlinx.coroutines.delay(RECONNECT_BASE_DELAY_MS * reconnectAttempts)
                    if (status != ConnStatus.CLOSED) doConnect()
                }.also { reconnectJob = it }
            } else {
                status = ConnStatus.CLOSED
                // 会话异常/超时等原因必须浮现（此前被静默丢弃，
                // 用户只能看到「已断开」且不知为何）
                if (reason != null) errorMessage = reason
                stopKeepAlive()
            }
        } else if (status == ConnStatus.CONNECTING) {
            status = ConnStatus.ERROR
            errorMessage = reason ?: "mosh 连接失败：客户端已退出"
            stopKeepAlive()
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
                kotlinx.coroutines.delay(MOSH_THEME_INJECT_DELAY_MS)
                injectThemeIfNeeded()
            }
        }
        status = ConnStatus.CONNECTED
        reconnectCount = 0
        errorMessage = null
        linkLostSeconds = 0
        startKeepAlive()
        // 网络切换免疫期 + 稳定期重置：
        // 刚连上 30 秒内网络事件不再触发主动重连；连接保持 30 秒后重连计数归零，
        // 避免「连上即退」场景下 onExit 自动重连无限循环
        networkImmuneUntilMs = nowMs() + NETWORK_IMMUNE_MS
        scope.launch {
            kotlinx.coroutines.delay(MOSH_STABLE_RESET_MS)
            if (status == ConnStatus.CONNECTED) reconnectAttempts = 0
        }
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
        // keepAliveActive 只挡同进程内的重复计数；前台服务被系统停掉（Android 15
        // dataSync 6h 超时、服务被杀）后 isActive() 为 false，必须重新拉起服务
        if (!keepAliveActive || !dev.termish.util.SessionKeepAlive.isActive()) {
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
        override suspend fun onOutput(data: ByteArray) {
            enqueueOutput(data)
        }

        override suspend fun onStderr(data: ByteArray) {
            enqueueOutput(data)
        }

        override fun onExitStatus(status: Int) {
            exitStatus = status
        }

        override fun onClosed(reason: String?) {
            if (status == ConnStatus.CLOSED) return // 用户主动断开
            // 意外断线：指数退避自动重连，终端缓冲保留
            val wasConnected = status == ConnStatus.CONNECTED || status == ConnStatus.AUTH
            if (autoReconnect && wasConnected && reconnectAttempts < RECONNECT_SSH_MAX) {
                reconnectAttempts++
                session = null
                status = ConnStatus.CONNECTING
                reconnectCount = reconnectAttempts
                errorMessage = null
                scope.launch {
                    kotlinx.coroutines.delay(RECONNECT_BASE_DELAY_MS * reconnectAttempts)
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
            // 超时按取消处理：弹窗随页面销毁/长期无人应答时不让连接协程悬挂
            val r = kotlinx.coroutines.withTimeoutOrNull(PROMPT_TIMEOUT_MS) { req.deferred.await() }
            if (authPrompt === req && r == null) authPrompt = null
            return r
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
                val ok = awaitHostKeyAnswer(req)
                // 接受即采纳新指纹：即使后续认证失败也不重复弹窗（TOFU 信任的是主机密钥）
                if (ok) repository.recordHostKey(host.id, hostKey.fingerprintSha256)
                return ok
            }
            // 首次连接：用户关闭了「首次连接确认」则直接信任（设置里仍可看到指纹）
            if (!repository.loadSettings().verifyHostKeyOnFirstUse) return true
            // TOFU：首次连接由用户确认
            val req = HostKeyRequest(hostKey)
            hostKeyPrompt = req
            val ok = awaitHostKeyAnswer(req)
            // 点信任即记录指纹，与认证成败解耦：否则认证失败（如密码错）时
            // 每次连接都重复弹授信窗
            if (ok) repository.recordHostKey(host.id, hostKey.fingerprintSha256)
            return ok
        }
    }

    /** 等待主机密钥确认并兜底清弹窗状态（正常应答已被 respondToHostKey 清过，超时按拒绝）。 */
    private fun awaitHostKeyAnswer(req: HostKeyRequest): Boolean {
        val r = runBlockingAwait(req.deferred)
        if (hostKeyPrompt === req) hostKeyPrompt = null
        return r
    }

    private fun runBlockingAwait(d: CompletableDeferred<Boolean>): Boolean =
        kotlinx.coroutines.runBlocking {
            // 与 onPrompt 同理：超时按拒绝处理，防止连接线程永久阻塞
            kotlinx.coroutines.withTimeoutOrNull(PROMPT_TIMEOUT_MS) { d.await() } ?: false
        }

    /** reader 协程投递输出：队列满则挂起施加背压（TCP 窗口收敛），不丢字节。 */
    private suspend fun enqueueOutput(data: ByteArray) {
        if (destroyed) return
        try {
            outputQueue.send(data)
        } catch (_: ClosedSendChannelException) {
            // destroy() 关闭队列后 send 抛 ClosedSendChannelException：会话已死，丢弃
        }
    }

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

    /**
     * 销毁控制器（从会话列表移除时调用）：关闭连接、关闭输出队列并取消协程
     * 作用域，回收延迟任务（稳定期重置/主题注入/重连退避）占用的资源。
     * 与 [close] 的区别：close 保留重入能力（scope 存活，可 reconnect()），
     * destroy 后控制器不可再用。
     */
    fun destroy() {
        close()
        destroyed = true
        outputQueue.close()
        scope.cancel()
    }

    fun isConnected(): Boolean = status == ConnStatus.CONNECTED || status == ConnStatus.AUTH

    /**
     * 网络切换（Wi-Fi ↔ 流量等）时由平台层调用：
     * - SSH：主动断开旧连接，走 onClosed 的自动重连路径（重置计数，避免等 TCP 超时）；
     * - Mosh：UDP 客户端 IP 变化后无法恢复，直接重建（重新 SSH bootstrap）。
     */
    fun onNetworkChanged(kind: NetworkChangeKind) {
        if (!autoReconnect) return
        // mosh：断网与跨网络切换都【不重建】——UDP 无连接 + 服务器从客户端新源
        // 地址学习回包目标 + 端口轮换，mosh 会在网络变化后自行恢复（原生 mosh
        // 的漫游能力）。只有客户端异常退出（onExit）才走自动重连。
        if (moshSession != null) return
        // SSH：只在「新网络已就绪」（传输切换）时主动断开快速重连；
        // onLost 时新网络未必就绪（尤其流量→Wi-Fi），纯断网交给 TCP 自然断开 +
        // onClosed 退避重连。
        if (kind == NetworkChangeKind.LOST) return
        val now = nowMs()
        if (now < networkImmuneUntilMs) return // 连接后免疫期：刚连上不折腾
        if (now - lastNetworkReconnectAtMs < NETWORK_DEBOUNCE_MS) return // 防抖：窗口内只主动重连一次
        lastNetworkReconnectAtMs = now
        when (status) {
            ConnStatus.CONNECTED -> {
                reconnectAttempts = 0
                session?.close()
            }
            // 连接/重连已在途中：网络刚切换，等当前流程完成即可（不重置计数）
            ConnStatus.CONNECTING, ConnStatus.AUTH -> {}
            else -> {}
        }
    }
}

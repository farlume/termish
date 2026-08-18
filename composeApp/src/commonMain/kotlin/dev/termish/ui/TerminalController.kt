package dev.termish.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.termish.data.Host
import dev.termish.data.HostRepository
import dev.termish.data.QuickCommand
import dev.termish.ssh.AuthPrompt
import dev.termish.ssh.HostKeyInfo
import dev.termish.ssh.MoshSession
import dev.termish.ssh.SshCallbacks
import dev.termish.ssh.SshConnection
import dev.termish.ssh.SshExecChannel
import dev.termish.ssh.SshSession
import dev.termish.ssh.createSshSession
import dev.termish.term.TerminalBuffer
import dev.termish.term.TerminalEmulator
import dev.termish.term.TerminalSelection
import dev.termish.term.argbToRgb
import dev.termish.ui.theme.TerminalThemes
import dev.termish.util.TermLog
import dev.termish.util.NetworkChangeKind
import dev.termish.util.SessionKeepAlive
import dev.termish.util.TermTrace
import dev.termish.util.ioDispatcher
import kotlin.concurrent.Volatile
import kotlin.random.Random
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
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
 * 终端会话控制器：持有终端状态（buffer / emulator / 连接状态）供 UI 观察，
 * 并把键盘输入路由到当前传输（SSH shell / mosh / herdr exec）。
 *
 * 连接编排（三模式建连、重连、herdr 探测/引导/降级、网络事件）在
 * [SessionConnector]——状态所有权留在这里（Compose 观察点不变），
 * connector 经同包 internal 访问读写。
 */
class TerminalController(
    val host: Host,
    internal val password: String?,
    internal val privateKeyPem: String?,
    internal val repository: HostRepository,
    /** 意外断线时自动重连（指数退避，最多 3 次）。 */
    internal val autoReconnect: Boolean = true,
    /** 连接错误文案提供器（随语言切换取最新 AppStrings）。 */
    private val strings: () -> AppStrings = { appStringsFor("en") },
    /** SSH 会话工厂（测试注入 fake；生产默认走平台引擎）。 */
    internal val sessionFactory: (SshConnection, SshCallbacks) -> SshSession = ::createSshSession,
) {
    val buffer = TerminalBuffer(80, 24, maxScrollbackLines = 10_000)
    val emulator = TerminalEmulator(buffer)
    val selection = TerminalSelection(buffer)

    var status by mutableStateOf(ConnStatus.IDLE)
        internal set
    var errorMessage by mutableStateOf<String?>(null)
        internal set
    /** 当前重连尝试次数：>0 且状态为 CONNECTING 时表示正在自动重连。 */
    var reconnectCount by mutableStateOf(0)
        internal set
    /** mosh 链路失联秒数（0=健康；达到阈值时 UI 显示「失去联系」banner，会话仍保持）。 */
    var linkLostSeconds by mutableStateOf(0)
        internal set
    var title by mutableStateOf(host.name)
        internal set
    var authPrompt by mutableStateOf<AuthPromptRequest?>(null)
        private set
    var hostKeyPrompt by mutableStateOf<HostKeyRequest?>(null)
        private set
    var exitStatus by mutableStateOf<Int?>(null)
        private set

    /** 变更序号：UI 据此判断是否需要重绘。 */
    var frame by mutableStateOf(0L)
        internal set

    /** 光标闪烁相位：纯渲染层动画状态，与协议显隐（buffer.cursorVisible）分离。 */
    var cursorBlinkPhase by mutableStateOf(true)
        private set

    /** OSC 52：远端程序写剪贴板时回调（由 UI 层接入系统剪贴板）。 */
    var onRemoteClipboard: ((String) -> Unit)? = null

    /** 自动探测到远端系统并已保存时回调（Termius 式识别；UI 据此刷新主机列表）。 */
    var onSystemDetected: ((Host) -> Unit)? = null

    /** herdr 是否可用（HERDR 模式探测：远端装了 herdr）。 */
    var herdrAvailable by mutableStateOf(false)
        internal set

    /** HERDR 模式：远端未安装 herdr（banner 显示安装引导）。 */
    var herdrNeedsInstall by mutableStateOf(false)
        internal set

    /** HERDR 安装进行中（banner 显示进度，避免重复触发）。 */
    var herdrInstalling by mutableStateOf(false)
        internal set

    /** HERDR 安装脚本的实时输出（引导卡片里展示；挂住时可见无新进展）。 */
    var herdrInstallLog by mutableStateOf("")
        internal set

    /** 本会话创建时的凭据签名：主机编辑后凭据变化即可据此判定旧会话过期。 */
    val credentialKey: String = credentialSignature(host, password, privateKeyPem)

    companion object {
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

    // ---- 传输通道（connector 读写；close 时统一释放）----
    internal var session: SshSession? = null
    internal var moshSession: MoshSession? = null
    /** HERDR 模式 SSH 兜底的 exec+pty 通道（herdr TUI 直接跑，无 shell 回显）。 */
    internal var herdrExec: SshExecChannel? = null
    /** HERDR 连接阶段：shell 输出抑制（决策前不渲染，避免提示符/命令回显割裂）。 */
    internal var herdrSuppressShellOutput = false
    /** Mosh 成功路径关闭 SSH 引导通道时短路 onClosed（避免误判为意外断线）。 */
    internal var swallowClosed = false
    internal val scope = CoroutineScope(ioDispatcher() + SupervisorJob())
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
    internal var lastCols = 80
    internal var lastRows = 24
    /** 会话唯一标识（同主机多会话区分；Compose key() 重组用）。 */
    val sessionId: String = "${host.id}:${Random.nextLong()}"
    internal var reconnectAttempts = 0
    private var keepAliveActive = false
    /** 自动重连的延迟任务：close() 时取消，防止关闭后仍被延迟协程拉起。 */
    internal var reconnectJob: Job? = null
    /** Mosh 主题注入：非空表示本会话开启（见 SessionConnector.prepareThemeSync）。 */
    internal var moshThemePayload: ByteArray? = null
    internal var moshThemeInjected = false
    /** 单调钟：网络免疫期/防抖用单调毫秒，避免墙钟被用户改时间/NTP 校正干扰。 */
    private val mono = TimeSource.Monotonic.markNow()
    internal fun nowMs(): Long = mono.elapsedNow().inWholeMilliseconds
    /** 连接成功后的网络事件免疫截止（单调毫秒）：期内不触发主动重连（防刚连上即断）。 */
    internal var networkImmuneUntilMs = 0L
    /** 最近一次主动重连时刻（单调毫秒）：15 秒防抖，避免网络抖动引发连续重连。 */
    internal var lastNetworkReconnectAtMs = 0L

    private val connector = SessionConnector(this, strings)

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
        // OSC 应答（10/11/4 颜色查询、DSR/DA 等）与键盘输入同路由：herdrExec 优先。
        // 此前直发 session——HERDR 降级路径（exec+pty 跑 herdr）下 herdr 的 OSC 4
        // 调色板查询应答被发到 shell stdin，readline 回显成满屏 "]4;i;rgb:…" 文本。
        emulator.onResponse = { bytes -> sendBytes(bytes) }
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
                val mark = TimeSource.Monotonic.markNow()
                while (mark.elapsedNow().inWholeMilliseconds < OUTPUT_BATCH_BUDGET_MS) {
                    val next = outputQueue.tryReceive().getOrNull() ?: break
                    emulator.write(next)
                }
                frame++
                yield()
            }
        }
    }

    fun connect(columns: Int, rows: Int) = connector.connect(columns, rows)

    /** 按最近一次窗口尺寸重连（保留屏幕缓冲）；用于退到后台后回前台恢复会话。 */
    fun reconnect() = connector.reconnect()

    /** HERDR 模式：远端未安装时，在已认证连接上执行官网安装脚本并继续连接。 */
    fun installHerdr() = connector.installHerdr()

    /** 是否允许自动重连（由打开会话时的设置决定）。 */
    val autoReconnectEnabled: Boolean get() = autoReconnect

    internal fun callbacks(trace: TermTrace.Span? = null) = object : SshCallbacks {
        override fun onTraceStep(step: String) {
            trace?.step(step)
        }
        override suspend fun onOutput(data: ByteArray) {
            // HERDR 连接阶段：shell 输出抑制（决策前不渲染，避免提示符/命令回显割裂）
            if (herdrSuppressShellOutput) return
            enqueueOutput(data)
        }

        override suspend fun onStderr(data: ByteArray) {
            enqueueOutput(data)
        }

        override fun onExitStatus(status: Int) {
            exitStatus = status
        }

        override fun onClosed(reason: String?) {
            if (swallowClosed) return // Mosh 成功路径主动关闭引导通道
            if (status == ConnStatus.CLOSED) return // 用户主动断开
            connector.onUnexpectedClose(reason)
        }

        override suspend fun onPrompt(prompt: AuthPrompt): List<String>? {
            val req = AuthPromptRequest(prompt)
            authPrompt = req
            // 超时按取消处理：弹窗随页面销毁/长期无人应答时不让连接协程悬挂
            val r = withTimeoutOrNull(PROMPT_TIMEOUT_MS) { req.deferred.await() }
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
                if (known == hostKey.fingerprintSha256) {
                    TermLog.d("ssh") { "hostkey ok ${host.name} ${hostKey.algorithm}" }
                    return true
                }
                // 指纹已变更：不再硬失败（否则改地址/服务器换钥后永远连不上、且无重置入口），
                // 改为弹窗让用户核对新旧指纹后决定。
                TermLog.w("ssh") { "hostkey CHANGED ${host.name}: $known -> ${hostKey.fingerprintSha256}" }
                val req = HostKeyRequest(hostKey, changed = true, previousFingerprint = known)
                hostKeyPrompt = req
                val ok = awaitHostKeyAnswer(req)
                // 接受即采纳新指纹：即使后续认证失败也不重复弹窗（TOFU 信任的是主机密钥）
                if (ok) repository.recordHostKey(host.id, hostKey.fingerprintSha256)
                return ok
            }
            // 首次连接：用户关闭了「首次连接确认」则直接信任（设置里仍可看到指纹）
            if (!repository.loadSettings().verifyHostKeyOnFirstUse) {
                TermLog.d("ssh") { "hostkey trust-on-first-use skipped (setting off) ${host.name}" }
                return true
            }
            // TOFU：首次连接由用户确认
            TermLog.d("ssh") { "hostkey TOFU prompt ${host.name} ${hostKey.fingerprintSha256}" }
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
        runBlocking {
            // 与 onPrompt 同理：超时按拒绝处理，防止连接线程永久阻塞
            withTimeoutOrNull(PROMPT_TIMEOUT_MS) { d.await() } ?: false
        }

    /** reader 协程投递输出：队列满则挂起施加背压（TCP 窗口收敛），不丢字节。 */
    internal suspend fun enqueueOutput(data: ByteArray) {
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
        val exec = herdrExec
        if (exec != null) exec.write(text.encodeToByteArray())
        else moshSession?.sendData(text.encodeToByteArray()) ?: session?.sendData(text.encodeToByteArray())
    }

    fun quickCommands(): List<QuickCommand> = host.quickCommands

    fun sendBytes(bytes: ByteArray) {
        val exec = herdrExec
        if (exec != null) exec.write(bytes)
        else moshSession?.sendData(bytes) ?: session?.sendData(bytes)
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

    internal fun startKeepAlive() {
        // keepAliveActive 只挡同进程内的重复计数；前台服务被系统停掉（Android 15
        // dataSync 6h 超时、服务被杀）后 isActive() 为 false，必须重新拉起服务
        if (!keepAliveActive || !SessionKeepAlive.isActive()) {
            keepAliveActive = true
            SessionKeepAlive.onSessionStart()
        }
    }

    internal fun stopKeepAlive() {
        if (keepAliveActive) {
            keepAliveActive = false
            SessionKeepAlive.onSessionEnd()
        }
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
        herdrExec?.close()
        herdrExec = null
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
     * 网络切换（Wi-Fi ↔ 流量等）时由平台层调用：语义见 [SessionConnector.onNetworkChanged]。
     */
    fun onNetworkChanged(kind: NetworkChangeKind) = connector.onNetworkChanged(kind)
}

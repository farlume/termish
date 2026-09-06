package dev.termish.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.termish.agent.AgentBridgeController
import dev.termish.data.ASR_API_KEY_ACCOUNT
import dev.termish.data.AgentProviderType
import dev.termish.data.AppSettings
import dev.termish.data.AsrProvider
import dev.termish.data.AsrProviderType
import dev.termish.data.Host
import dev.termish.data.HostRepository
import dev.termish.data.SECRET_SERVICE
import dev.termish.data.SecretStore
import dev.termish.data.ThemeMode
import dev.termish.data.asrKeyAccount
import dev.termish.data.newId
import dev.termish.data.resolveCredentials
import dev.termish.data.secretAccountFor
import dev.termish.notify.NotificationCenter
import dev.termish.notify.NotificationEvent
import dev.termish.notify.NotificationPermissionState
import dev.termish.notify.rememberNotificationPermissionController
import dev.termish.screen.MAX_SCREEN_RECONNECT_ATTEMPTS
import dev.termish.screen.ScreenSession
import dev.termish.screen.ScreenSessionMessages
import dev.termish.screen.ScreenUiState
import dev.termish.screen.fallbackScreenQuality
import dev.termish.screen.isUnstableScreenStream
import dev.termish.ssh.AuthPrompt
import dev.termish.ssh.HostKeyInfo
import dev.termish.ssh.SftpSession
import dev.termish.ssh.SshCallbacks
import dev.termish.ssh.SshConnection
import dev.termish.ssh.createSftpSession
import dev.termish.ui.theme.TerminalThemes
import dev.termish.ui.theme.TermishTheme
import dev.termish.util.BackgroundProtectionVendor
import dev.termish.util.LocalTerminalFont
import dev.termish.util.SessionKeepAlive
import dev.termish.util.TermLog
import dev.termish.util.TerminalFont
import dev.termish.util.backgroundProtectionState
import dev.termish.util.backgroundProtectionVendor
import dev.termish.util.ioDispatcher
import dev.termish.util.monospaceFontFamily
import dev.termish.util.observeAppLifecycle
import dev.termish.util.observeNetworkChange
import dev.termish.util.openApplicationSettings
import dev.termish.util.shouldShowBackgroundProtectionGuide
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock

private enum class HomeTab { HOSTS, CONNECTIONS, SETTINGS }

private const val AGENT_IDLE_TIMEOUT_MS = 60_000L

private fun ScreenStrings.toSessionMessages(): ScreenSessionMessages =
    ScreenSessionMessages(
        connectionFailed = connectionFailed,
        readChannelFailed = readChannelFailed,
        tcpPortMissing = tcpPortMissing,
        tcpChannelFailed = tcpChannelFailed,
        tcpDisconnected = tcpDisconnected,
        screenInUse = screenInUse,
        ffmpegMissing = ffmpegMissing,
        unsupportedOs = unsupportedOs,
        relayUpgradeRequired = relayUpgradeRequired,
        displayMissing = displayMissing,
        serviceNotRunning = serviceNotRunning,
        waylandHint = waylandHint,
        waylandDependenciesMissing = waylandDependenciesMissing,
        screenAsleepHint = screenAsleepHint,
        screenLockedHint = screenLockedHint,
        firstFrameTimeout = firstFrameTimeout,
        decoderInitializationFailed = decoderInitializationFailed,
        decoderNoOutput = decoderNoOutput,
        decodingFailed = decodingFailed,
        playerUnsupported = playerUnsupported,
    )

/** 极简底栏项：图标 + 等宽字体小标签，选中=主题绿，无胶囊指示器。 */
@Composable
private fun HomeTabItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    mono: FontFamily,
    modifier: Modifier = Modifier,
    badge: Int = 0,
    badgeAlert: Boolean = false,
    onClick: () -> Unit,
) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier.clickable(onClick = onClick).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BadgedBox(
            badge = {
                if (badge > 0) {
                    Badge(
                        containerColor =
                            if (badgeAlert) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        contentColor =
                            if (badgeAlert) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary,
                    ) {
                        Text(if (badge > 99) "99+" else "$badge", fontFamily = mono)
                    }
                }
            },
        ) {
            Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(22.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = mono,
            color = color,
        )
    }
}

private sealed interface Screen {
    data object Home : Screen

    data class Edit(
        val hostId: String?,
    ) : Screen

    data class Agents(
        val hostId: String,
        val context: AgentLaunchContext? = null,
    ) : Screen

    /** 直接持有 controller 引用：会话由 SessionManager 管理，跨页面存活。 */
    data object Terminal : Screen
}

/** 设置页二级页（统一由 AppRoot 管理开关：返回链 + 全屏 + 底部 tab 隐藏）。 */
enum class SettingsSubPage {
    TERMINAL,
    NOTIFICATION,
    DIAGNOSTICS,
    SNIPPETS,
    VOICE,
    PRIVACY,
}

/** 屏幕会话条目（远程画面推流）。 */
data class ScreenSessionEntry(
    val host: Host,
    /** 创建该屏幕会话的终端 tab 会话 id：小窗只在该 tab 显示。 */
    val ownerSessionId: String,
    val session: ScreenSession?,
    val uiState: ScreenUiState,
    /** 条目首次创建时间；推流重连和参数切换时保持不变。 */
    val createdAt: Long = Clock.System.now().toEpochMilliseconds(),
)

@Composable
fun AppRoot(repository: HostRepository) {
    // 旧版 Agent 供应商迁移：type=DEEPSEEK（单一 DeepSeek）→ OPENAI 类型 +
    // anthropic 兼容端点（claude 可用 /anthropic，opencode/pi 走 openai 兼容），
    // 参考 tuiniverse 的 Provider 模型。旧 id/名称/baseUrl 保持不变。
    fun migrateLegacyProviders(s: AppSettings): AppSettings {
        val providers = s.agentProviders
        val needsMigration =
            providers.any { provider ->
                provider.type == AgentProviderType.DEEPSEEK ||
                    provider.piProvider.isBlank() &&
                    provider.baseUrl.contains("api.deepseek.com")
            }
        if (!needsMigration) return s
        val migrated =
            providers.map { p ->
                val isDeepSeek = p.type == AgentProviderType.DEEPSEEK || p.baseUrl.contains("api.deepseek.com")
                if (!isDeepSeek) {
                    p
                } else {
                    p.copy(
                        type = AgentProviderType.OPENAI,
                        anthropicBaseUrl = p.anthropicBaseUrl.ifBlank { "${p.baseUrl.trimEnd('/')}/anthropic" },
                        piProvider = p.piProvider.ifBlank { "deepseek" },
                    )
                }
            }
        return s.copy(agentProviders = migrated)
    }

    // 语音识别服务旧配置（单实例 asrResourceId）迁移到 provider 列表：
    // 首次启动把旧资源 ID + 旧密钥搬进列表，避免用户重配
    fun migrateLegacyAsr(s: AppSettings): AppSettings {
        if (s.asrProviders.isNotEmpty() || s.asrResourceId.isBlank()) return s
        val legacyKey = SecretStore.get(SECRET_SERVICE, ASR_API_KEY_ACCOUNT)
        if (legacyKey.isNullOrBlank()) return s
        val p =
            AsrProvider(
                id = newId(),
                type = AsrProviderType.VOLC_STREAMING,
                name = "",
                resourceId = s.asrResourceId,
                enabled = true,
            )
        SecretStore.set(SECRET_SERVICE, asrKeyAccount(p.id), legacyKey)
        return s.copy(asrProviders = listOf(p))
    }

    remember(repository) {
        val stored = repository.loadSettings()
        val migrated = migrateLegacyProviders(migrateLegacyAsr(stored))
        if (migrated != stored) repository.saveSettings(migrated)
    }
    val settings by repository.appSettings.collectAsState()
    val notificationPermissionController = rememberNotificationPermissionController()
    var notificationPermissionState by remember { mutableStateOf(NotificationPermissionState.UNKNOWN) }
    var backgroundProtection by remember { mutableStateOf(backgroundProtectionState()) }
    val backgroundVendor = remember { backgroundProtectionVendor() }
    var showBackgroundProtectionGuide by remember { mutableStateOf(false) }
    var hosts by remember { mutableStateOf(repository.listHosts()) }
    val navigation = remember { NavigationStack<Screen>(Screen.Home) }
    val screen = navigation.current
    val scope = rememberCoroutineScope()

    fun navigate(target: Screen) {
        navigation.push(target)
    }

    fun navigateBack() {
        navigation.pop()
    }

    // 语言文案（提前声明供 connectSftp 等 lambda 使用）
    val appStrings = remember(settings.language) { appStringsFor(settings.language) }
    // 连接错误文案随语言即时切换：SessionManager 跨重组复用，须经 State 取最新值
    val currentStrings = rememberUpdatedState(appStrings)
    val sessionManager = remember { SessionManager(repository, strings = { currentStrings.value }) }

    LaunchedEffect(notificationPermissionController) {
        notificationPermissionController.refresh { notificationPermissionState = it }
    }

    /** 终端页当前显示的 tab（SSH 会话或 SFTP，同主机多会话切换用）。 */
    var currentTab by remember { mutableStateOf<SessionTab?>(null) }

    /** tab 切换历史（tab id 栈）：返回时弹栈回到上一个 tab，栈空才回首页。 */
    val tabHistory = remember { mutableStateListOf<String>() }

    /** 等待连接完成后再跳转的会话（连接期间卡片头像转圈）。 */
    var pendingNavigate by remember { mutableStateOf<TerminalController?>(null) }

    /** 终端 + 菜单「收藏夹」对话框：host + 收藏路径列表（null = 关闭）。 */
    var favoritesDialog by remember { mutableStateOf<Pair<Host, List<String>>?>(null) }

    /** 屏幕会话条目（远程画面推流）。 */
    val screenSessions = remember { mutableStateListOf<ScreenSessionEntry>() }

    /** 每台主机最多一个画面重连任务，避免旧任务完成后关闭刚建立的新通道。 */
    val screenReconnectJobs = remember { mutableMapOf<String, Job>() }

    /** 从主机卡点“画面”时，首帧到达后自动展开全屏；普通终端菜单启动不受影响。 */
    var screenFullscreenRequestHostId by remember { mutableStateOf<String?>(null) }

    /** SFTP：选主机覆盖层 / 当前会话 / 认证与主机密钥弹窗。 */
    var sftpPickerVisible by remember { mutableStateOf(false) }
    var sftpAuth by remember { mutableStateOf<AuthPromptRequest?>(null) }
    var sftpHostKey by remember { mutableStateOf<HostKeyRequest?>(null) }

    /** Agent 控制连接跨页面保留；弹窗也必须由 AppRoot 持有，不能捕获已销毁页面状态。 */
    var agentAuth by remember { mutableStateOf<Pair<String, AuthPromptRequest>?>(null) }
    var agentHostKey by remember { mutableStateOf<Pair<String, HostKeyRequest>?>(null) }
    val agentControllers = remember { mutableStateMapOf<String, AgentBridgeController>() }
    val agentControllerPool =
        remember {
            IdleResourcePool<String, String, AgentBridgeController>(
                scope = scope,
                idleTimeoutMillis = AGENT_IDLE_TIMEOUT_MS,
                closeResource = { controller ->
                    if (agentAuth?.first == controller.host.id) {
                        agentAuth?.second?.deferred?.complete(null)
                        agentAuth = null
                    }
                    if (agentHostKey?.first == controller.host.id) {
                        agentHostKey?.second?.deferred?.complete(false)
                        agentHostKey = null
                    }
                    if (agentControllers[controller.host.id] === controller) {
                        agentControllers.remove(controller.host.id)
                    }
                    controller.close()
                    TermLog.i("agent") { "idle connection closed ${controller.host.name}" }
                },
                // 页面离开后可以回收空闲控制连接，但远端任务运行中或等待审批时
                // 必须继续监听事件，否则用户收不到完成/审批通知。
                canCloseResource = { controller ->
                    !controller.hasActiveWork
                },
            )
        }
    val snackbarHostState = remember { SnackbarHostState() }

    fun acquireAgentController(host: Host): AgentBridgeController =
        agentControllerPool.acquire(
            key = host.id,
            signature = sessionManager.signatureFor(host),
        ) {
            val callbacks =
                object : SshCallbacks {
                    override suspend fun onOutput(data: ByteArray) {}

                    override suspend fun onStderr(data: ByteArray) {}

                    override fun onExitStatus(status: Int) {}

                    override fun onClosed(reason: String?) {}

                    override suspend fun onPrompt(prompt: AuthPrompt): List<String>? {
                        agentAuth?.second?.deferred?.complete(null)
                        val request = AuthPromptRequest(prompt)
                        agentAuth = host.id to request
                        return awaitAuthPromptAnswer(request.deferred).also {
                            if (agentAuth?.second === request) agentAuth = null
                        }
                    }

                    override fun verifyHostKey(hostKey: HostKeyInfo): Boolean {
                        val known =
                            repository.getHost(host.id)?.knownHostFingerprint
                                ?: host.knownHostFingerprint
                        if (known == hostKey.fingerprintSha256) return true
                        if (known == null && !repository.loadSettings().verifyHostKeyOnFirstUse) return true
                        agentHostKey?.second?.deferred?.complete(false)
                        val request = HostKeyRequest(hostKey, known != null, known)
                        agentHostKey = host.id to request
                        val accepted = awaitHostKeyPromptAnswer(request.deferred)
                        if (agentHostKey?.second === request) agentHostKey = null
                        if (accepted) repository.touchConnected(host.id, hostKey.fingerprintSha256)
                        return accepted
                    }
                }
            AgentBridgeController(host, repository, callbacks, scope).also { controller ->
                controller.onApprovalRequested = { approval ->
                    val strings = currentStrings.value.nativeAgents
                    val title = approval.title.ifBlank { strings.approvalRequired }
                    NotificationCenter.post(
                        NotificationEvent.AGENT_TASK,
                        "Termish",
                        strings.approvalNotification(host.name, title),
                        id = "agent-approval:${host.id}".hashCode(),
                    )
                }
                controller.onTaskSettled = { session ->
                    val strings = currentStrings.value.nativeAgents
                    val title = session?.title?.ifBlank { strings.title } ?: strings.title
                    NotificationCenter.post(
                        NotificationEvent.AGENT_TASK,
                        "Termish",
                        strings.taskCompletedNotification(host.name, title),
                        id = "agent-complete:${host.id}".hashCode(),
                    )
                }
                agentControllers[host.id] = controller
            }
        }

    lateinit var applyScreenStreamConfig: (Host, Int, String, Boolean) -> Unit

    /**
     * 建立 SFTP 会话（认证/主机密钥确认走全局弹窗），成功后回调 [onEstablished]。
     * 供首次连接与断线重连复用；失败由调用方处理（首次=Snackbar，重连=保持 banner）。
     * 定义在 connectSftp 之前：局部函数不能前向引用。
     * suspend：createSftpSession 是阻塞连接，必须切 IO 线程（调用方在 Main scope，
     * 否则重连按钮点击后 UI 冻结几秒——「点了没反应」）。
     */
    suspend fun establishSftp(
        host: Host,
        onEstablished: (SftpSession, Any) -> Unit,
    ) {
        // 本次连接的身份代次标识：onClosed 凭此区分「本代连接意外断开」与
        // 「重连/换新时旧连接被主动关闭」（close 会同步触发旧回调，
        // 不比对代次就会把断开 banner 误标回刚重连成功的会话）
        val connectionToken = Any()
        val (pw, key) = resolveCredentials(host)
        val callbacks =
            object : SshCallbacks {
                override suspend fun onOutput(data: ByteArray) {}

                override suspend fun onStderr(data: ByteArray) {}

                override fun onExitStatus(status: Int) {}

                override fun onClosed(reason: String?) {
                    // SFTP 断开主动推送：立即置 disconnected（banner 红条 + 重连入口）。
                    // 代次比对：重连/换新/移除时被 close 的旧连接回调仍会触发，
                    // 但条目已登记新代次（或已移除）→ 忽略，不误标
                    val entry =
                        sessionManager.sftpSessions
                            .firstOrNull { it.host.id == host.id && it.session != null }
                    if (entry == null || entry.connectionToken !== connectionToken) return
                    scope.launch {
                        TermLog.w("sftp") { "sftp closed ${host.name}: ${reason ?: "unknown"}" }
                        entry.uiState.disconnected = true
                    }
                }

                override suspend fun onPrompt(prompt: AuthPrompt): List<String>? {
                    val req = AuthPromptRequest(prompt)
                    sftpAuth = req
                    return req.deferred.await()
                }

                override fun verifyHostKey(hostKey: HostKeyInfo): Boolean {
                    // 与终端连接同源：已授信指纹匹配自动通过，不重复弹窗
                    val known =
                        repository.getHost(host.id)?.knownHostFingerprint
                            ?: host.knownHostFingerprint
                    if (known != null) {
                        if (known == hostKey.fingerprintSha256) return true
                        // 指纹变更：弹窗让用户核对新旧指纹
                        val req = HostKeyRequest(hostKey, changed = true, previousFingerprint = known)
                        sftpHostKey = req
                        val accepted = awaitHostKeyPromptAnswer(req.deferred)
                        if (accepted) repository.touchConnected(host.id, hostKey.fingerprintSha256)
                        return accepted
                    }
                    // 首次连接：设置关闭首次确认则直接信任
                    if (!repository.loadSettings().verifyHostKeyOnFirstUse) return true
                    val req = HostKeyRequest(hostKey)
                    sftpHostKey = req
                    val accepted = awaitHostKeyPromptAnswer(req.deferred)
                    if (accepted) repository.touchConnected(host.id, hostKey.fingerprintSha256)
                    return accepted
                }
            }
        val conn =
            SshConnection(
                host = host.hostname,
                port = host.port,
                username = host.username,
                password = pw,
                privateKeyPem = key,
                keepAliveSeconds = repository.loadSettings().keepaliveSeconds,
            )
        val session = withContext(ioDispatcher()) { createSftpSession(conn, callbacks) }
        TermLog.i("sftp") { "connected ${host.name} ${host.hostname}:${host.port}" }
        onEstablished(session, connectionToken)
    }

    /**
     * 建立屏幕推流会话（认证/主机密钥确认走全局弹窗，与 SFTP 同模式）。
     * 成功后注册条目并切到屏幕 tab；失败由调用方提示。
     */
    suspend fun establishScreen(
        host: Host,
        uiState: ScreenUiState = ScreenUiState(),
        onEstablished: (ScreenSession, ScreenUiState) -> Unit,
    ) {
        val (pw, key) = resolveCredentials(host)
        val callbacks =
            object : SshCallbacks {
                override suspend fun onOutput(data: ByteArray) {}

                override suspend fun onStderr(data: ByteArray) {}

                override fun onExitStatus(status: Int) {}

                override fun onClosed(reason: String?) {}

                override suspend fun onPrompt(prompt: AuthPrompt): List<String>? {
                    val req = AuthPromptRequest(prompt)
                    sftpAuth = req
                    return req.deferred.await()
                }

                override fun verifyHostKey(hostKey: HostKeyInfo): Boolean {
                    val known =
                        repository.getHost(host.id)?.knownHostFingerprint
                            ?: host.knownHostFingerprint
                    if (known != null) {
                        if (known == hostKey.fingerprintSha256) return true
                        val req = HostKeyRequest(hostKey, changed = true, previousFingerprint = known)
                        sftpHostKey = req
                        val accepted = awaitHostKeyPromptAnswer(req.deferred)
                        if (accepted) repository.touchConnected(host.id, hostKey.fingerprintSha256)
                        return accepted
                    }
                    if (!repository.loadSettings().verifyHostKeyOnFirstUse) return true
                    val req = HostKeyRequest(hostKey)
                    sftpHostKey = req
                    val accepted = awaitHostKeyPromptAnswer(req.deferred)
                    if (accepted) repository.touchConnected(host.id, hostKey.fingerprintSha256)
                    return accepted
                }
            }
        val conn =
            SshConnection(
                host = host.hostname,
                port = host.port,
                username = host.username,
                password = pw,
                privateKeyPem = key,
                connectTimeoutMillis = 10_000,
                keepAliveSeconds = 0,
            )
        val session =
            ScreenSession(
                conn,
                callbacks,
                scope,
                uiState,
                messages = currentStrings.value.screen.toSessionMessages(),
                // 断流自动重连：同主机只允许一个任务，且替换前再次核对会话代次。
                // 否则并发旧任务会轮流关闭刚建立的新通道，形成固定周期断开循环。
                onStreamLost = {
                    scope.launch schedule@{
                        // 安装/升级 relay 会主动重启远端服务，旧视频通道断开是预期
                        // 生命周期；安装完成回调负责唯一一次重建，不能再并发排一条
                        // 3 秒自动重连去关闭刚建立的新通道。
                        if (uiState.installing) {
                            TermLog.i("screen") { "skip reconnect while installing ${host.name}" }
                            return@schedule
                        }
                        if (screenReconnectJobs[host.id]?.isActive == true) {
                            TermLog.i("screen") { "skip duplicate reconnect ${host.name}" }
                            return@schedule
                        }
                        val sourceUiState = uiState
                        val reconnectJob =
                            scope.launch reconnect@{
                                delay(3_000)
                                val current = screenSessions.firstOrNull { it.host.id == host.id }
                                if (current?.uiState !== sourceUiState) return@reconnect

                                val now = Clock.System.now().toEpochMilliseconds()
                                val unstable = isUnstableScreenStream(now, sourceUiState.videoReadyAtMillis)
                                val attempt = if (unstable) sourceUiState.streamReconnectAttempts + 1 else 1
                                if (attempt > MAX_SCREEN_RECONNECT_ATTEMPTS) {
                                    TermLog.w("screen") {
                                        "auto-reconnect stopped ${host.name} after $MAX_SCREEN_RECONNECT_ATTEMPTS attempts"
                                    }
                                    return@reconnect
                                }

                                // 原画/高清若刚出帧就断，逐级回退；原画实际宽度不超过
                                // 1920 时不反向放大。稳定运行后的偶发断线保持用户画质。
                                val readyWidth =
                                    sourceUiState.player
                                        ?.videoDims
                                        ?.value
                                        ?.first ?: 0
                                val currentQuality = sourceUiState.streamQuality
                                val fallbackQuality = fallbackScreenQuality(currentQuality, readyWidth, unstable)
                                if (fallbackQuality != currentQuality) {
                                    val fallbackScale = ScreenSession.scaleForQuality(fallbackQuality)
                                    current.session?.setStreamConfig(sourceUiState.streamFps, fallbackScale)
                                    sourceUiState.streamQuality = fallbackQuality
                                    TermLog.w("screen") {
                                        "unstable stream fallback ${host.name}: quality=$currentQuality->$fallbackQuality"
                                    }
                                }

                                val owner = current.ownerSessionId
                                TermLog.i("screen") { "auto-reconnect ${host.name} attempt=$attempt" }
                                try {
                                    establishScreen(host) { newSession, newUi ->
                                        val latest = screenSessions.firstOrNull { it.host.id == host.id }
                                        if (latest?.uiState !== sourceUiState) {
                                            newSession.close()
                                            return@establishScreen
                                        }
                                        latest.session?.close()
                                        screenSessions.removeAll { it.host.id == host.id }
                                        newUi.streamReconnectAttempts = attempt
                                        newUi.streamFps = sourceUiState.streamFps
                                        newUi.streamQuality = sourceUiState.streamQuality
                                        newUi.preferredStreamFps = sourceUiState.preferredStreamFps
                                        newUi.adaptiveFpsChangedAtMillis = sourceUiState.adaptiveFpsChangedAtMillis
                                        screenSessions.add(
                                            ScreenSessionEntry(host, owner, newSession, newUi, current.createdAt),
                                        )
                                    }
                                } catch (e: Exception) {
                                    TermLog.w("screen") { "auto-reconnect failed ${host.name}: $e" }
                                }
                            }
                        screenReconnectJobs[host.id] = reconnectJob
                    }
                },
                onAdaptiveFpsRequested = { fps ->
                    applyScreenStreamConfig(
                        host,
                        fps,
                        ScreenSession.scaleForQuality(uiState.streamQuality),
                        false,
                    )
                },
            )
        withContext(ioDispatcher()) { session.start() }
        onEstablished(session, uiState)
    }

    // 终端 + 菜单「屏幕」：建推流会话，**留在当前 tab**——小窗出现在终端页，
    // 点小窗全屏按钮在当前页展开（不跳 tab）
    val openScreen: (Host) -> Unit = { host ->
        screenReconnectJobs.remove(host.id)?.cancel()
        val ownerId = (currentTab as? SessionTab.Terminal)?.controller?.sessionId ?: ""
        // 连接开始即注册占位条目：不同主机首次打开画面时也能立即显示该主机的
        // 全屏连接反馈，不必等 SSH + 推流建好后才隐藏终端按钮和系统状态栏。
        val pendingUiState = ScreenUiState()
        screenSessions.firstOrNull { it.host.id == host.id }?.session?.close()
        screenSessions.removeAll { it.host.id == host.id }
        screenSessions.add(ScreenSessionEntry(host, ownerId, null, pendingUiState))
        scope.launch {
            try {
                establishScreen(host, pendingUiState) { session, uiState ->
                    // 同主机若已发起更新请求，旧请求完成后不得覆盖新条目。
                    val pending = screenSessions.firstOrNull { it.host.id == host.id }
                    if (pending?.uiState !== pendingUiState) {
                        session.close()
                        return@establishScreen
                    }
                    screenSessions.removeAll { it.host.id == host.id }
                    screenSessions.add(ScreenSessionEntry(host, ownerId, session, uiState, pending.createdAt))
                }
            } catch (e: Exception) {
                screenSessions.removeAll {
                    it.host.id == host.id && it.uiState === pendingUiState
                }
                if (screenFullscreenRequestHostId == host.id) screenFullscreenRequestHostId = null
                snackbarHostState.showSnackbar(appStrings.screen.connecting + " " + (e.message ?: ""))
            }
        }
    }

    // 屏幕推流服务安装（引导卡片按钮）：复用已认证会话跑安装脚本（流式日志），
    // 装完重建推流会话重连。与 herdr 安装引导同模式。
    val installScreenService: (Host, String?) -> Unit = { host, sudoPassword ->
        screenReconnectJobs.remove(host.id)?.cancel()
        val entry = screenSessions.firstOrNull { it.host.id == host.id && it.session != null }
        if (entry != null) {
            entry.session?.installService(
                sudoPassword = sudoPassword,
                onLog = { log -> entry.uiState.installLog = log },
                onComplete = { ok ->
                    if (ok) {
                        screenReconnectJobs.remove(host.id)?.cancel()
                        // 装完重建推流会话（读流重连）
                        scope.launch {
                            try {
                                establishScreen(host) { session, uiState ->
                                    val latest = screenSessions.firstOrNull { it.host.id == host.id }
                                    if (latest?.uiState !== entry.uiState) {
                                        session.close()
                                        return@establishScreen
                                    }
                                    latest.session?.close()
                                    screenSessions.removeAll { it.host.id == host.id }
                                    uiState.preferredStreamFps = entry.uiState.preferredStreamFps
                                    uiState.adaptiveFpsChangedAtMillis = entry.uiState.adaptiveFpsChangedAtMillis
                                    screenSessions.add(
                                        ScreenSessionEntry(host, entry.ownerSessionId, session, uiState, entry.createdAt),
                                    )
                                }
                            } catch (e: Exception) {
                                snackbarHostState.showSnackbar(appStrings.screen.connecting + " " + (e.message ?: ""))
                            }
                        }
                    } else {
                        entry.uiState.installing = false
                        scope.launch {
                            snackbarHostState.showSnackbar(appStrings.screen.installService + " 失败，查看日志")
                        }
                    }
                },
            )
        }
    }

    // 屏幕重连（就地全屏/重连按钮）：重建推流会话（关旧会话防泄漏）
    val reconnectScreenForHost: (Host) -> Unit = { host ->
        screenReconnectJobs.remove(host.id)?.cancel()
        val existingEntry = screenSessions.firstOrNull { it.host.id == host.id }
        val ownerId = existingEntry?.ownerSessionId ?: ""
        scope.launch {
            try {
                establishScreen(host) { session, uiState ->
                    screenSessions.firstOrNull { it.host.id == host.id }?.session?.close()
                    screenSessions.removeAll { it.host.id == host.id }
                    uiState.preferredStreamFps = existingEntry?.uiState?.preferredStreamFps ?: 0
                    uiState.adaptiveFpsChangedAtMillis = existingEntry?.uiState?.adaptiveFpsChangedAtMillis ?: 0
                    screenSessions.add(
                        ScreenSessionEntry(
                            host,
                            ownerId,
                            session,
                            uiState,
                            existingEntry?.createdAt ?: Clock.System.now().toEpochMilliseconds(),
                        ),
                    )
                }
            } catch (e: Exception) {
                snackbarHostState.showSnackbar(appStrings.screen.connecting + " " + (e.message ?: ""))
            }
        }
    }

    // 关闭屏幕会话并移除条目（全屏 ✕ / Agent 页关闭按钮）
    val closeScreenForHost: (Host) -> Unit = { host ->
        screenReconnectJobs.remove(host.id)?.cancel()
        screenSessions.firstOrNull { it.host.id == host.id }?.session?.close()
        screenSessions.removeAll { it.host.id == host.id }
    }

    // 帧率/画质切换：SSH 写远端 relay 配置 → 重建会话生效。
    // userInitiated=false 时保留用户上限，后续稳定窗口可逐级恢复。
    // ⚠️ 档位必须设置在【重建后的新 uiState】上：重建（establishScreen）
    // 会创建新 ScreenUiState（默认档位），设在旧对象上会被替换掉，
    // 右上角数字永远不变（用户反馈）
    applyScreenStreamConfig = { host, fps, scale, userInitiated ->
        screenReconnectJobs.remove(host.id)?.cancel()
        lateinit var configJob: Job
        configJob =
            scope.launch {
                // 菜单连续切帧率/画质时只应用最后一次选择。此前每次点击都会启动
                // 独立 SSH + direct-tcpip；慢请求在被新请求替换后仍可能晚到并踢掉
                // 当前画面，表现为固定数秒重复断开。
                delay(250)
                val entry = screenSessions.firstOrNull { it.host.id == host.id }
                // 旧条目 uiState 也同步档位：断流自动重连（onStreamLost）
                // 用旧 uiState 重建时会保留新档位，不被 30 覆盖（用户反馈：
                // 切帧率后右上角仍显示 30——自动重连与重建竞态）
                entry?.uiState?.streamFps = fps
                entry?.uiState?.streamQuality = ScreenSession.qualityIndexFor(scale)
                if (userInitiated) entry?.uiState?.preferredStreamFps = fps
                entry?.session?.setStreamConfig(fps, scale)
                establishScreen(host) { session, uiState ->
                    if (screenReconnectJobs[host.id] !== configJob) {
                        session.close()
                        return@establishScreen
                    }
                    screenSessions.firstOrNull { it.host.id == host.id }?.session?.close()
                    screenSessions.removeAll { it.host.id == host.id }
                    uiState.streamFps = fps
                    uiState.streamQuality = ScreenSession.qualityIndexFor(scale)
                    uiState.preferredStreamFps =
                        if (userInitiated) {
                            fps
                        } else {
                            entry?.uiState?.preferredStreamFps?.takeIf { it > 0 } ?: fps
                        }
                    uiState.adaptiveFpsChangedAtMillis =
                        entry?.uiState?.adaptiveFpsChangedAtMillis ?: 0
                    screenSessions.add(
                        ScreenSessionEntry(
                            host,
                            entry?.ownerSessionId ?: "",
                            session,
                            uiState,
                            entry?.createdAt ?: Clock.System.now().toEpochMilliseconds(),
                        ),
                    )
                }
            }
        screenReconnectJobs[host.id] = configJob
    }
    val streamConfigChange: (Host, Int, String) -> Unit = { host, fps, scale ->
        applyScreenStreamConfig(host, fps, scale, true)
    }

    // 覆盖层选主机后：建立 SFTP 会话（认证/主机密钥弹窗走全局 sftpAuth/sftpHostKey）。
    // [initialPath] 非空时打开后直接定位到该目录（终端「文件管理」菜单：当前工作目录）。
    val connectSftp: (Host, String?) -> Unit = { host, initialPath ->
        sftpPickerVisible = false
        scope.launch {
            try {
                establishSftp(host) { session, token ->
                    val entry = sessionManager.addSftp(host, session, token)
                    // 持久化收藏恢复到会话（终端 + 菜单收藏夹读取同一份）
                    repository.loadFavorites(host.id).forEach { entry.uiState.favorites.add(it) }
                    if (!initialPath.isNullOrBlank()) {
                        entry.uiState.path = initialPath
                    }
                    // 与 entry 共用同一 uiState：浏览路径变化能反映到持久化（退后台保存）
                    // 从终端页进入：压当前终端 tab 进返回历史（返回时回到终端，
                    // 而非直接回主页——与 tab 栏切换同一返回链）
                    (currentTab as? SessionTab.Terminal)?.let { tabHistory.add(it.id) }
                    currentTab = SessionTab.Sftp(host, session, entry.uiState)
                    navigate(Screen.Terminal)
                }
            } catch (e: Exception) {
                snackbarHostState.showSnackbar(appStrings.sftpConnectFailed(e.message ?: ""))
            }
        }
    }

    /** 主机能力入口：同一主机、同一启动类型优先复用；否则创建独立会话。 */
    fun openHostTerminal(
        host: Host,
        launchMode: TerminalLaunchMode,
    ) {
        val signature = sessionManager.signatureFor(host)
        val existing =
            sessionManager.sessions.lastOrNull {
                it.host.id == host.id && it.launchMode == launchMode && it.credentialKey == signature
            }
        if (existing != null) {
            currentTab = SessionTab.Terminal(existing)
            navigate(Screen.Terminal)
            return
        }
        pendingNavigate =
            sessionManager.open(host, settings.autoReconnect, launchMode) {
                hosts = repository.listHosts()
            }
    }

    /** 主机卡画面入口需要一个终端页宿主；画面 SSH 本身仍使用独立连接。 */
    val openScreenFromHost: (Host) -> Unit = { host ->
        val signature = sessionManager.signatureFor(host)
        val owner =
            sessionManager.sessions.lastOrNull {
                it.host.id == host.id && it.credentialKey == signature
            } ?: sessionManager.open(host, settings.autoReconnect, TerminalLaunchMode.SHELL) {
                hosts = repository.listHosts()
            }
        currentTab = SessionTab.Terminal(owner)
        screenFullscreenRequestHostId = host.id
        navigate(Screen.Terminal)
        openScreen(host)
    }

    /** 重新进入已有画面会话：复用推流，只补齐终端页宿主并展开全屏。 */
    val showScreenSession: (ScreenSessionEntry) -> Unit = { entry ->
        val signature = sessionManager.signatureFor(entry.host)
        val owner =
            sessionManager.sessions.firstOrNull { it.sessionId == entry.ownerSessionId }
                ?: sessionManager.sessions.lastOrNull {
                    it.host.id == entry.host.id && it.credentialKey == signature
                } ?: sessionManager.open(
                entry.host,
                settings.autoReconnect,
                TerminalLaunchMode.SHELL,
            ) {
                hosts = repository.listHosts()
            }
        if (entry.ownerSessionId != owner.sessionId) {
            val index = screenSessions.indexOfFirst { it.uiState === entry.uiState }
            if (index >= 0) screenSessions[index] = entry.copy(ownerSessionId = owner.sessionId)
        }
        currentTab = SessionTab.Terminal(owner)
        screenFullscreenRequestHostId = entry.host.id
        navigate(Screen.Terminal)
    }

    LaunchedEffect(pendingNavigate) {
        val target = pendingNavigate ?: return@LaunchedEffect
        // TUI 会话（herdr 工作台 / 启动命令）不预连：列表页无终端画布只能
        // 80x24 起步，herdr/tmux 按错误尺寸布局后再 resize 会整体重排跳动；
        // 直接进终端页等画布量到实际尺寸后建连，首帧即正确布局
        // （纯 shell 预连无此问题——resize 只是把提示符换行）
        if (target.launchMode == TerminalLaunchMode.HERDR || target.host.startupCommand.isNotBlank()) {
            currentTab = SessionTab.Terminal(target)
            navigate(Screen.Terminal)
            pendingNavigate = null
            return@LaunchedEffect
        }
        // 列表页没有终端画布：用默认尺寸先行建连（跳转后 TerminalView 会 resize
        // 到实际画布尺寸），否则会话停留在 IDLE 永远无法连接
        if (target.status == ConnStatus.IDLE) {
            target.connect(80, 24)
        }
        // 轮询等待连接结果：成功跳转终端页，失败留在列表（头像停止转圈）
        while (target.status == ConnStatus.CONNECTING || target.status == ConnStatus.AUTH) {
            delay(200)
        }
        pendingNavigate = null
        if (target.status == ConnStatus.CONNECTED) {
            currentTab = SessionTab.Terminal(target)
            navigate(Screen.Terminal)
        } else if (target.status == ConnStatus.ERROR) {
            // 连接失败（IP 不可达 / 认证失败等）：留在列表并提示原因
            snackbarHostState.showSnackbar(target.errorMessage ?: appStrings.hostsConnectFailed)
        }
    }
    // 恢复上次运行时的会话列表（仅一次；进程死亡连接必死，恢复为未连接可重连）
    var sessionsRestored by remember { mutableStateOf(false) }
    if (!sessionsRestored) {
        sessionManager.restoreRecent(hosts, settings.autoReconnect) {
            hosts = repository.listHosts()
        }
        sessionsRestored = true
    }

    // iOS：退到桌面后系统挂起进程、掐断 socket；回前台时自动重连活跃会话（缓冲保留）。
    // Android 由前台服务保活，对应实现为空操作。
    val disposeNetwork =
        observeNetworkChange { kind ->
            // 网络事件：SSH 对默认网络变化先探测再决定是否重连；mosh 靠 UDP 漫游自愈
            // （实现与 NetworkChangeKind 注释一致：mosh 仅客户端异常退出时才走自动重连）
            sessionManager.sessions.forEach { it.onNetworkChanged(kind) }
        }
    DisposableEffect(Unit) {
        // 通知中心：注入设置读取器；前后台状态供后台事件通知过滤；
        // 「重新连接」动作 → 找到该主机会话重连（无活跃会话则打开主机页）
        NotificationCenter.settingsProvider = { repository.loadSettings() }
        NotificationCenter.foreground = true
        NotificationCenter.onReconnectRequest = { hostId ->
            TermLog.i("notify") { "reconnect action hostId=$hostId" }
            scope.launch {
                sessionManager.sessions
                    .firstOrNull { it.host.id == hostId }
                    ?.let { controller ->
                        if (controller.status == ConnStatus.CLOSED ||
                            controller.status == ConnStatus.ERROR
                        ) {
                            controller.reconnect()
                        }
                    }
            }
        }
        val dispose =
            observeAppLifecycle { foreground ->
                TermLog.d("life") { "foreground=$foreground" }
                NotificationCenter.foreground = foreground
                if (foreground) {
                    notificationPermissionController.refresh { notificationPermissionState = it }
                    backgroundProtection = backgroundProtectionState()
                    // 先读取恢复策略再重建服务登记：Android 的 VERIFY 依据是“后台期间
                    // 服务曾被停”，登记恢复后 isActive 会变化，但仍需验证旧 socket。
                    val recovery = SessionKeepAlive.foregroundSshRecovery()
                    if (!SessionKeepAlive.isActive()) {
                        sessionManager.restoreKeepAliveRegistrations()
                    }
                    sessionManager.reconnectDroppedSessions(recovery)
                } else {
                    sessionManager.noteBackgrounded()
                    // 退后台即保存最新 SFTP 浏览路径：杀 App 重进后恢复到上次目录
                    sessionManager.persistNow()
                }
            }
        onDispose {
            agentControllerPool.closeAll()
            dispose()
            disposeNetwork()
        }
    }

    // 全局返回栈：一级页面弹栈回到真实来源；设置二级页回设置；主页非主机 tab 回主机 tab。
    // 子页面若漏拦截返回，根层也只弹一层，不再把目标写死成首页。
    var homeTab by remember { mutableStateOf(HomeTab.HOSTS) }
    var settingsSubPage by remember { mutableStateOf<SettingsSubPage?>(null) }
    val homeHasBackTarget = screen == Screen.Home && (settingsSubPage != null || homeTab != HomeTab.HOSTS)
    PlatformBackHandler(enabled = navigation.canPop || homeHasBackTarget) {
        when {
            navigation.canPop -> navigateBack()
            settingsSubPage != null -> settingsSubPage = null
            homeTab != HomeTab.HOSTS -> homeTab = HomeTab.HOSTS
        }
    }

    fun refreshHosts() {
        hosts = repository.listHosts()
    }

    val terminalTheme = TerminalThemes.ALL.getOrElse(settings.terminalThemeIndex) { TerminalThemes.ALL[0] }
    val agentSessionItems =
        agentControllers.values
            .filter { it.currentSession != null || it.busy || it.pendingApprovals.isNotEmpty() }
            .map { HostSessionItem.Agent(it) }
    val activeSessionCount =
        sessionManager.sessions.count { isActiveStatus(it.status) } +
            sessionManager.sftpSessions.count { it.session != null } +
            screenSessions.size +
            agentSessionItems.count { it.isActive }
    val hasConnectedSshSession =
        sessionManager.sessions.any { it.status == ConnStatus.CONNECTED && it.moshSession == null }

    LaunchedEffect(
        hasConnectedSshSession,
        settings.backgroundProtectionPrompted,
        backgroundProtection,
        backgroundVendor,
    ) {
        if (
            shouldShowBackgroundProtectionGuide(
                state = backgroundProtection,
                vendor = backgroundVendor,
                alreadyPrompted = settings.backgroundProtectionPrompted,
                hasConnectedSshSession = hasConnectedSshSession,
            )
        ) {
            repository.updateSettings { it.copy(backgroundProtectionPrompted = true) }
            showBackgroundProtectionGuide = true
        }
    }

    CompositionLocalProvider(
        LocalAppStrings provides appStrings,
        LocalTerminalFont provides TerminalFont.byId(settings.terminalFontId),
    ) {
        TermishTheme(settings.theme) {
            // 状态栏图标颜色只看当前页面实际背景亮度：
            // - 终端 tab → 按终端主题背景亮度
            // - 其余页面 → 按应用主题亮度
            val pageDark =
                if (screen is Screen.Terminal && currentTab is SessionTab.Terminal) {
                    terminalTheme.background().luminance() < 0.5f
                } else {
                    settings.theme != ThemeMode.LIGHT
                }
            PlatformStatusBarIcons(lightIcons = pageDark)
            Box(Modifier.fillMaxSize()) {
                // 会话级弹窗全局渲染：认证 / 主机密钥确认在首页连接等待时也能弹出，
                // 不必先进入终端页（否则连接卡在转圈却看不到授权请求）
                sessionManager.sessions.forEach { controller ->
                    controller.authPrompt?.let { prompt ->
                        AuthPromptDialog(prompt.prompt) { answers ->
                            controller.respondToPrompt(answers)
                        }
                    }
                    controller.hostKeyPrompt?.let { hk ->
                        HostKeyDialog(
                            key = hk.key,
                            changed = hk.changed,
                            previousFingerprint = hk.previousFingerprint,
                        ) { accept ->
                            controller.respondToHostKey(accept)
                        }
                    }
                }
                AnimatedContent(
                    targetState = screen,
                    transitionSpec = {
                        // 终端包含持续刷新的画布/原生视频面，只做短淡变，避免滑动期间
                        // 两份 Surface 同时移动；普通层级页面按 push/pop 方向成对滑动。
                        if (initialState is Screen.Terminal || targetState is Screen.Terminal) {
                            fadeIn(tween(140)).togetherWith(fadeOut(tween(120)))
                        } else if (navigation.direction == NavigationDirection.BACKWARD) {
                            (slideInHorizontally(tween(220)) { -it / 5 } + fadeIn(tween(180)))
                                .togetherWith(
                                    slideOutHorizontally(tween(200)) { it / 4 } + fadeOut(tween(160)),
                                )
                        } else {
                            (slideInHorizontally(tween(220)) { it / 4 } + fadeIn(tween(180)))
                                .togetherWith(
                                    slideOutHorizontally(tween(200)) { -it / 5 } + fadeOut(tween(160)),
                                )
                        }
                    },
                    contentKey = { it },
                    label = "page-transition",
                ) { targetScreen ->
                    when (val s = targetScreen) {
                        Screen.Home -> {
                            Scaffold(
                                // 各页面 Header 自行避让状态栏，底部 NavigationBar 自行避让导航条，
                                // 外层不再重复施加（否则标题上方出现双倍状态栏高度）
                                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                                bottomBar = {
                                    // 设置二级页打开时全屏：隐藏底部 tab（二级页由返回链统一关闭）
                                    if (settingsSubPage == null) {
                                        // 自绘极简底栏：无胶囊指示器，选中=主题绿，等宽字体小标签
                                        val mono = monospaceFontFamily()
                                        Column {
                                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                            Row(
                                                Modifier
                                                    .fillMaxWidth()
                                                    .background(MaterialTheme.colorScheme.surface)
                                                    .navigationBarsPadding(),
                                            ) {
                                                HomeTabItem(
                                                    appStrings.appTabHosts,
                                                    Icons.Default.Dns,
                                                    homeTab == HomeTab.HOSTS,
                                                    mono,
                                                    Modifier.weight(1f),
                                                ) {
                                                    homeTab = HomeTab.HOSTS
                                                }
                                                HomeTabItem(
                                                    appStrings.appTabConnections,
                                                    Icons.Default.Cable,
                                                    homeTab == HomeTab.CONNECTIONS,
                                                    mono,
                                                    Modifier.weight(1f),
                                                    badge = activeSessionCount,
                                                    badgeAlert =
                                                        agentSessionItems.any {
                                                            it.controller.pendingApprovals.isNotEmpty()
                                                        },
                                                ) {
                                                    homeTab = HomeTab.CONNECTIONS
                                                }
                                                HomeTabItem(
                                                    appStrings.appTabSettings,
                                                    Icons.Default.Settings,
                                                    homeTab == HomeTab.SETTINGS,
                                                    mono,
                                                    Modifier.weight(1f),
                                                ) {
                                                    homeTab = HomeTab.SETTINGS
                                                }
                                            }
                                        }
                                    }
                                },
                            ) { padding ->
                                Box(Modifier.padding(padding)) {
                                    Crossfade(
                                        targetState = homeTab,
                                        animationSpec = tween(160),
                                        label = "home-tab-transition",
                                    ) { targetHomeTab ->
                                        when (targetHomeTab) {
                                            HomeTab.HOSTS ->
                                                HostListScreen(
                                                    hosts = hosts,
                                                    hostSessions =
                                                        (
                                                            sessionManager.sessions.map { HostSessionItem.Terminal(it) } +
                                                                sessionManager.sftpSessions.map {
                                                                    HostSessionItem.Sftp(
                                                                        it.host,
                                                                        it.session,
                                                                        it.createdAt,
                                                                    )
                                                                } +
                                                                screenSessions.map { HostSessionItem.Screen(it) } +
                                                                agentSessionItems
                                                        ).groupBy { it.hostId },
                                                    onAdd = { navigate(Screen.Edit(null)) },
                                                    onEdit = { navigate(Screen.Edit(it.id)) },
                                                    onConnect = { host ->
                                                        openHostTerminal(host, TerminalLaunchMode.SHELL)
                                                    },
                                                    onOpenHerdr = { host ->
                                                        openHostTerminal(host, TerminalLaunchMode.HERDR)
                                                    },
                                                    onConnectBatch = { batch ->
                                                        // 批处理连接：逐个建立会话（后台运行），不跳转终端页
                                                        batch.forEach { host ->
                                                            sessionManager.open(host, settings.autoReconnect) {
                                                                hosts = repository.listHosts()
                                                            }
                                                        }
                                                    },
                                                    onDisconnect = { host ->
                                                        // 断开该主机全部会话：终端断开保留 + SFTP 释放（与「全部关闭」一致）
                                                        sessionManager.closeAllForHost(host.id)
                                                        closeScreenForHost(host)
                                                        agentControllerPool.remove(host.id)
                                                    },
                                                    onOpenSession = { controller ->
                                                        // 卡片点击 = 用当前配置连这台主机：配置/凭据已变更的旧会话不复用，
                                                        // 用当前配置新建（旧会话保留在连接页，可手动关闭/重入）
                                                        if (controller.credentialKey !=
                                                            sessionManager.signatureFor(controller.host)
                                                        ) {
                                                            val fresh =
                                                                sessionManager.open(
                                                                    controller.host,
                                                                    settings.autoReconnect,
                                                                    controller.launchMode,
                                                                ) {
                                                                    hosts = repository.listHosts()
                                                                }
                                                            pendingNavigate = fresh
                                                        } else {
                                                            currentTab = SessionTab.Terminal(controller)
                                                            navigate(Screen.Terminal)
                                                        }
                                                    },
                                                    onOpenSftp = { host, session ->
                                                        // 用 entry 的 uiState（若已存在）：重新进入不重置浏览状态/路径
                                                        val entry =
                                                            sessionManager.sftpSessions.firstOrNull {
                                                                it.host.id ==
                                                                    host.id
                                                            }
                                                        currentTab =
                                                            SessionTab.Sftp(host, session, entry?.uiState ?: SftpUiState())
                                                        navigate(Screen.Terminal)
                                                    },
                                                    onOpenScreenSession = showScreenSession,
                                                    onStartSftp = { host -> connectSftp(host, null) },
                                                    onOpenScreen = openScreenFromHost,
                                                    onCloseAllSessions = { host ->
                                                        // 关闭该主机全部会话：终端断开保留 + SFTP 释放
                                                        sessionManager.closeAllForHost(host.id)
                                                        closeScreenForHost(host)
                                                        agentControllerPool.remove(host.id)
                                                    },
                                                    onDelete = { host ->
                                                        sessionManager.closeForHost(host.id)
                                                        closeScreenForHost(host)
                                                        agentControllerPool.remove(host.id)
                                                        SecretStore.delete(
                                                            SECRET_SERVICE,
                                                            secretAccountFor(host.id, "password"),
                                                        )
                                                        SecretStore.delete(
                                                            SECRET_SERVICE,
                                                            secretAccountFor(host.id, "privateKey"),
                                                        )
                                                        repository.deleteHost(host.id)
                                                        refreshHosts()
                                                    },
                                                    onAgents = { host -> navigate(Screen.Agents(host.id)) },
                                                )

                                            HomeTab.CONNECTIONS ->
                                                ConnectionsScreen(
                                                    sessions =
                                                        sessionManager.sessions.map { HostSessionItem.Terminal(it) } +
                                                            sessionManager.sftpSessions.map {
                                                                HostSessionItem.Sftp(
                                                                    it.host,
                                                                    it.session,
                                                                    it.createdAt,
                                                                )
                                                            } +
                                                            screenSessions.map { HostSessionItem.Screen(it) } +
                                                            agentSessionItems,
                                                    onOpen = { item ->
                                                        when (item) {
                                                            is HostSessionItem.Terminal -> {
                                                                currentTab = SessionTab.Terminal(item.controller)
                                                                navigate(Screen.Terminal)
                                                            }
                                                            is HostSessionItem.Sftp -> {
                                                                // 连接页重入：用 entry 的 uiState（浏览状态/路径保留）
                                                                val entry =
                                                                    sessionManager.sftpSessions.firstOrNull {
                                                                        it.host.id == item.host.id &&
                                                                            it.session === item.session
                                                                    }
                                                                currentTab =
                                                                    SessionTab.Sftp(
                                                                        item.host,
                                                                        item.session,
                                                                        entry?.uiState ?: SftpUiState(),
                                                                    )
                                                                navigate(Screen.Terminal)
                                                            }
                                                            is HostSessionItem.Screen -> showScreenSession(item.entry)
                                                            is HostSessionItem.Agent ->
                                                                navigate(Screen.Agents(item.controller.host.id))
                                                        }
                                                    },
                                                    onClose = { item ->
                                                        when (item) {
                                                            is HostSessionItem.Terminal -> {
                                                                if (item.isActive) {
                                                                    sessionManager.disconnect(item.controller)
                                                                } else {
                                                                    sessionManager.remove(item.controller)
                                                                }
                                                            }
                                                            is HostSessionItem.Sftp -> {
                                                                // 与终端同语义两段式：活跃=断开保留（重连恢复路径），
                                                                // 已断开（session=null）=从列表移除
                                                                val entry =
                                                                    sessionManager.sftpSessions
                                                                        .firstOrNull {
                                                                            it.session === item.session ||
                                                                                (
                                                                                    item.session == null &&
                                                                                        it.host.id == item.host.id
                                                                                )
                                                                        }
                                                                when {
                                                                    entry == null -> {}
                                                                    entry.session != null ->
                                                                        sessionManager.disconnectSftp(
                                                                            entry,
                                                                        )
                                                                    else -> sessionManager.closeSftp(entry)
                                                                }
                                                            }
                                                            is HostSessionItem.Screen -> closeScreenForHost(item.entry.host)
                                                            is HostSessionItem.Agent ->
                                                                agentControllerPool.remove(item.controller.host.id)
                                                        }
                                                    },
                                                )

                                            HomeTab.SETTINGS ->
                                                SettingsScreen(
                                                    settings = settings,
                                                    onChange = { new ->
                                                        // 即改即存
                                                        repository.saveSettings(new)
                                                    },
                                                    repository = repository,
                                                    notificationPermissionState = notificationPermissionState,
                                                    notificationPermissionController = notificationPermissionController,
                                                    onNotificationPermissionStateChange = {
                                                        notificationPermissionState = it
                                                    },
                                                    backgroundProtectionState = backgroundProtection,
                                                    subPage = settingsSubPage,
                                                    onOpenSub = { settingsSubPage = it },
                                                )
                                        }
                                    }
                                }
                            }
                        }

                        is Screen.Edit -> {
                            val existing = hosts.firstOrNull { it.id == s.hostId }
                            HostEditScreen(
                                existing = existing,
                                repository = repository,
                                onSave = { host, pw, key ->
                                    if (pw.isNotBlank()) {
                                        SecretStore.set(
                                            SECRET_SERVICE,
                                            secretAccountFor(host.id, "password"),
                                            pw,
                                        )
                                    }
                                    if (key.isNotBlank()) {
                                        SecretStore.set(
                                            SECRET_SERVICE,
                                            secretAccountFor(host.id, "privateKey"),
                                            key,
                                        )
                                    }
                                    repository.upsertHost(host)
                                    refreshHosts()
                                    navigateBack()
                                },
                                onCancel = ::navigateBack,
                            )
                        }

                        is Screen.Agents -> {
                            val host = hosts.firstOrNull { it.id == s.hostId }
                            if (host == null) {
                                navigateBack()
                            } else {
                                val agentController = acquireAgentController(host)
                                AgentScreen(
                                    host = host,
                                    repository = repository,
                                    controller = agentController,
                                    initialContext = s.context,
                                    onRelease = { agentControllerPool.release(host.id) },
                                    onBack = ::navigateBack,
                                    // 屏幕远控（复用终端页推流基础设施）：会话条目/回调
                                    // 由 AppRoot 持有，Agent 页内全屏播放、关闭/重连/安装/档位
                                    // 走同一套 establishScreen 流程（Agent 连接不因切页而断）
                                    screenEntry = screenSessions.firstOrNull { it.host.id == host.id },
                                    onStartScreen = openScreen,
                                    onCloseScreen = closeScreenForHost,
                                    onReconnectScreen = reconnectScreenForHost,
                                    onInstallScreenService = installScreenService,
                                    onScreenConfigChange = streamConfigChange,
                                )
                            }
                        }

                        // 返回主页不断开：默认后台运行，会话保留在 SessionManager，
                        // 由前台服务保活，从「连接」页可重新进入（终端缓冲原样保留）
                        // 终端页 tabs = 全部会话（跨主机）：连任何主机都进同一个终端页，
                        // tab 栏以「user@host + 状态点」区分（Termius 式全局会话）
                        is Screen.Terminal -> {
                            // 终端会话 tab 不过滤状态：断开/失败也保留（tab 内状态点体现），
                            // 关闭 tab 时才从列表移除；否则创建 SFTP 后重组会把非活跃终端 tab 丢掉
                            val terminalTabs = sessionManager.sessions.map { SessionTab.Terminal(it) }
                            val sftpTabs =
                                sessionManager.sftpSessions
                                    .map { SessionTab.Sftp(it.host, it.session, it.uiState) }
                            val allTabs = terminalTabs + sftpTabs
                            val current =
                                currentTab?.takeIf { tab ->
                                    tab.id in allTabs.map { it.id }
                                } ?: allTabs.firstOrNull()
                            if (current != null) {
                                val tabs = allTabs
                                // 「+」新增会话归属：当前选中会话的主机（SFTP tab 用其主机）
                                val currentHost =
                                    when (current) {
                                        is SessionTab.Terminal -> current.controller.host
                                        is SessionTab.Sftp -> current.host
                                        is SessionTab.Screen -> current.host
                                    }
                                // 屏幕会话条目（小窗/全屏主机名同源：不依赖 current tab）
                                val pipEntry =
                                    (current as? SessionTab.Terminal)?.let { termTab ->
                                        screenSessions.firstOrNull {
                                            it.ownerSessionId == termTab.controller.sessionId
                                        }
                                    }
                                TerminalScreen(
                                    tabs = tabs,
                                    current = current,
                                    theme = terminalTheme,
                                    settings = settings,
                                    repository = repository,
                                    onBack = {
                                        // 返回优先弹 tab 历史（SFTP/屏幕 → 回到上一个终端 tab），
                                        // 栈空才回首页
                                        val prevId = tabHistory.removeLastOrNull()
                                        val target = prevId?.let { id -> allTabs.firstOrNull { it.id == id } }
                                        if (target != null && target.id != currentTab?.id) {
                                            currentTab = target
                                        } else {
                                            // 页面退出动画仍会渲染终端约 120ms；此时清空会让
                                            // current 回退到 allTabs.firstOrNull()，第二个 tab
                                            // 离场时就闪出第一个 tab。保留选中项直到下次入口覆盖。
                                            refreshHosts()
                                            navigateBack()
                                        }
                                    },
                                    onSwitchTab = { it ->
                                        // 手动点 tab 是平级切换，不写返回历史；只有“文件管理”等
                                        // 功能入口显式进入二级 tab 时才由入口记录来源。否则
                                        // Terminal → SFTP → Terminal 会把两边都压栈，返回来回跳。
                                        currentTab = it
                                    },
                                    onAddSession = {
                                        val c =
                                            sessionManager.open(currentHost, settings.autoReconnect) {
                                                hosts = repository.listHosts()
                                            }
                                        pendingNavigate = c
                                    },
                                    onCloseTab = { tab ->
                                        when (tab) {
                                            is SessionTab.Terminal -> sessionManager.remove(tab.controller)
                                            is SessionTab.Sftp -> {
                                                sessionManager.sftpSessions
                                                    .firstOrNull { it.session === tab.session }
                                                    ?.let { sessionManager.closeSftp(it) }
                                            }
                                            is SessionTab.Screen -> {
                                                val entry = screenSessions.firstOrNull { it.session === tab.session }
                                                if (entry != null) {
                                                    entry.session?.close()
                                                    screenSessions.remove(entry)
                                                }
                                            }
                                        }
                                        // 清理历史栈中失效的 tab id（已关闭的会话）
                                        val liveIds =
                                            (
                                                sessionManager.sessions
                                                    .map { SessionTab.Terminal(it) } +
                                                    sessionManager.sftpSessions
                                                        .map { SessionTab.Sftp(it.host, it.session, it.uiState) } +
                                                    screenSessions.map {
                                                        SessionTab.Screen(
                                                            it.host,
                                                            it.ownerSessionId,
                                                            it.session,
                                                            it.uiState,
                                                        )
                                                    }
                                            ).map { it.id }
                                                .toSet()
                                        tabHistory.removeAll { it !in liveIds }
                                        val remaining =
                                            (
                                                sessionManager.sessions
                                                    .map { SessionTab.Terminal(it) } +
                                                    sessionManager.sftpSessions
                                                        .map { SessionTab.Sftp(it.host, it.session, it.uiState) }
                                            ).firstOrNull { it.id != tab.id }
                                        currentTab = remaining
                                        if (remaining == null) {
                                            refreshHosts()
                                            navigateBack()
                                        }
                                    },
                                    onOpenSftpPicker = { sftpPickerVisible = true },
                                    // 文件管理：直接对当前主机建 SFTP 会话并切到 SFTP tab
                                    // （复用 connectSftp 全流程：认证弹窗 / 主机密钥 / 断线重连）
                                    onOpenSftpForHost = connectSftp,
                                    // 收藏夹：读取持久化收藏，弹列表跳转（无收藏则提示）
                                    onOpenFavorites = { host ->
                                        val favs = repository.loadFavorites(host.id)
                                        if (favs.isEmpty()) {
                                            scope.launch {
                                                snackbarHostState.showSnackbar(
                                                    appStrings.sftpExt.favoritesEmpty,
                                                )
                                            }
                                        } else {
                                            favoritesDialog = host to favs
                                        }
                                    },
                                    // 屏幕：建推流会话并切到屏幕 tab
                                    onOpenScreen = openScreen,
                                    // 屏幕推流服务安装（引导卡片按钮）：流式日志 → 装完重建会话重连
                                    onInstallScreenService = installScreenService,
                                    // 屏幕重连（就地全屏）：按主机重建会话
                                    onReconnectScreenForHost = reconnectScreenForHost,
                                    // 全屏帧率/画质切换：SSH 写远端 relay 配置 → 重建会话生效。
                                    // ⚠️ 档位必须设置在【重建后的新 uiState】上：重建（establishScreen）
                                    // 会创建新 ScreenUiState（默认档位），设在旧对象上会被替换掉，
                                    // 右上角数字永远不变（用户反馈）
                                    onStreamConfigChange = streamConfigChange,
                                    // 屏幕断线重连：重建会话替换 tab
                                    onReconnectScreen = { tab ->
                                        scope.launch {
                                            try {
                                                establishScreen(tab.host) { session, uiState ->
                                                    val existingEntry =
                                                        screenSessions.firstOrNull { it.host.id == tab.host.id }
                                                    existingEntry?.session?.close()
                                                    screenSessions.removeAll { it.host.id == tab.host.id }
                                                    screenSessions.add(
                                                        ScreenSessionEntry(
                                                            tab.host,
                                                            tab.ownerSessionId,
                                                            session,
                                                            uiState,
                                                            existingEntry?.createdAt
                                                                ?: Clock.System.now().toEpochMilliseconds(),
                                                        ),
                                                    )
                                                }
                                            } catch (e: Exception) {
                                                snackbarHostState.showSnackbar(
                                                    appStrings.screen.connecting + " " + (e.message ?: ""),
                                                )
                                            }
                                        }
                                    },
                                    // 终端页小窗：只显示**属于当前终端 tab** 的屏幕会话（切走即隐藏）
                                    screenPip = pipEntry?.uiState,
                                    screenFullscreenRequestHostId = screenFullscreenRequestHostId,
                                    onScreenFullscreenRequestConsumed = {
                                        screenFullscreenRequestHostId = null
                                    },
                                    // 屏幕会话自己的主机（全屏头部显示，不依赖 current tab）
                                    pipHost = pipEntry?.host,
                                    // 小窗 ✕：关闭当前屏幕会话（销毁推流 + 移除条目）
                                    onCloseScreenPip = {
                                        val ownerId = (current as? SessionTab.Terminal)?.controller?.sessionId
                                        if (ownerId != null) {
                                            val entry = screenSessions.firstOrNull { it.ownerSessionId == ownerId }
                                            if (entry != null) {
                                                entry.session?.close()
                                                screenSessions.remove(entry)
                                            }
                                        }
                                    },
                                    // 收藏变更：SFTP 页增删收藏即落盘
                                    onFavoritesChanged = { host, favs ->
                                        repository.saveFavorites(host.id, favs)
                                    },
                                    // 浏览路径即时持久化：导航即保存（退后台保存为兜底）
                                    onSftpPathChanged = { _, _ -> sessionManager.persistNow() },
                                    // 终端输出/SFTP 远端路径直达同主机 Agent，不经过剪贴板或文件中转。
                                    onAskAgent = { host, context -> navigate(Screen.Agents(host.id, context)) },
                                    // SFTP 断线重连：重建会话替换 tab（保留 uiState 的路径/列表）
                                    onReconnectSftp = { tab ->
                                        // session 可空（进程重启恢复条目）：host.id + 引用双重匹配，
                                        // 避免多个 null-session 条目时错配
                                        val entry =
                                            sessionManager.sftpSessions.find {
                                                it.host.id == tab.host.id && it.session === tab.session
                                            }
                                        scope.launch {
                                            try {
                                                establishSftp(tab.host) { newSession, token ->
                                                    entry?.let { sessionManager.reconnectSftp(it, newSession, token) }
                                                    currentTab = SessionTab.Sftp(tab.host, newSession, tab.uiState)
                                                    // 重连成功：清除重连中/断开状态，SftpContent 继续用原路径浏览
                                                    tab.uiState.reconnecting = false
                                                    tab.uiState.disconnected = false
                                                }
                                            } catch (e: Exception) {
                                                // 重连失败：保持 banner，用户可点按钮重试
                                                tab.uiState.reconnecting = false
                                                tab.uiState.loadError = e.message
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }

                // New SFTP connection 覆盖层（盖在当前页面之上）
                if (sftpPickerVisible) {
                    SftpHostPickerOverlay(
                        hosts = hosts,
                        onDismiss = { sftpPickerVisible = false },
                        onSelect = { host -> connectSftp(host, null) },
                    )
                }

                // 收藏夹对话框（终端 + 菜单入口）：列收藏路径，点击直达
                favoritesDialog?.let { (host, favs) ->
                    SftpFavoritesDialog(
                        favorites = favs,
                        onOpen = { favorite ->
                            favoritesDialog = null
                            connectSftp(host, favorite)
                        },
                        onRemove = { favorite ->
                            val remaining = favs - favorite
                            favoritesDialog = host to remaining
                            repository.saveFavorites(host.id, remaining)
                        },
                        onDismiss = { favoritesDialog = null },
                    )
                }

                // SFTP 认证 / 主机密钥确认（复用全局弹窗）
                sftpAuth?.let { req ->
                    AuthPromptDialog(req.prompt) { answers ->
                        req.deferred.complete(answers)
                        sftpAuth = null
                    }
                }
                sftpHostKey?.let { req ->
                    HostKeyDialog(
                        key = req.key,
                        changed = req.changed,
                        previousFingerprint = req.previousFingerprint,
                    ) { accept ->
                        req.deferred.complete(accept)
                        sftpHostKey = null
                    }
                }

                // Agent 控制器可在离开页面后保留一分钟；认证请求因此也必须全局渲染。
                agentAuth?.second?.let { req ->
                    AuthPromptDialog(req.prompt) { answers ->
                        req.deferred.complete(answers)
                        if (agentAuth?.second === req) agentAuth = null
                    }
                }
                agentHostKey?.second?.let { req ->
                    HostKeyDialog(
                        key = req.key,
                        changed = req.changed,
                        previousFingerprint = req.previousFingerprint,
                    ) { accept ->
                        req.deferred.complete(accept)
                        if (agentHostKey?.second === req) agentHostKey = null
                    }
                }

                if (showBackgroundProtectionGuide) {
                    BackgroundProtectionDialog(
                        vendor = backgroundVendor,
                        onOpenSettings = {
                            showBackgroundProtectionGuide = false
                            openApplicationSettings()
                        },
                        onDismiss = { showBackgroundProtectionGuide = false },
                    )
                }

                TermishSnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

@Composable
private fun BackgroundProtectionDialog(
    vendor: BackgroundProtectionVendor,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalAppStrings.current
    val vendorHint =
        when (vendor) {
            BackgroundProtectionVendor.OPPO_FAMILY -> s.permissions.backgroundOppoHint
            BackgroundProtectionVendor.XIAOMI_FAMILY -> s.permissions.backgroundXiaomiHint
            BackgroundProtectionVendor.VIVO_FAMILY -> s.permissions.backgroundVivoHint
            BackgroundProtectionVendor.SAMSUNG -> s.permissions.backgroundSamsungHint
            BackgroundProtectionVendor.GENERIC -> s.permissions.backgroundGenericHint
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.permissions.backgroundTitle) },
        text = {
            Column {
                Text(s.permissions.backgroundBody)
                Text(
                    vendorHint,
                    modifier = Modifier.padding(top = 12.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onOpenSettings) { Text(s.permissions.openSettings) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(s.permissions.notNow) }
        },
    )
}

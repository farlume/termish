package dev.termish.ui

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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TextButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.termish.data.ASR_API_KEY_ACCOUNT
import dev.termish.data.AppSettings
import dev.termish.data.AsrProvider
import dev.termish.data.AsrProviderType
import dev.termish.data.Host
import dev.termish.data.HostAuthMethod
import dev.termish.data.HostRepository
import dev.termish.data.SECRET_SERVICE
import dev.termish.data.SecretStore
import dev.termish.data.ThemeMode
import dev.termish.data.asrKeyAccount
import dev.termish.data.newId
import dev.termish.data.secretAccountFor
import dev.termish.notify.NotificationCenter
import dev.termish.notify.NotificationEvent
import dev.termish.ssh.AuthPrompt
import dev.termish.ssh.HostKeyInfo
import dev.termish.ssh.SshCallbacks
import dev.termish.ssh.SftpSession
import dev.termish.ssh.SshConnection
import dev.termish.ssh.createSftpSession
import dev.termish.ui.theme.TermishTheme
import dev.termish.ui.theme.TerminalThemes
import dev.termish.util.monospaceFontFamily
import dev.termish.util.observeAppLifecycle
import dev.termish.util.observeNetworkChange
import dev.termish.util.SessionKeepAlive
import dev.termish.util.TermLog
import dev.termish.util.TerminalFont
import dev.termish.util.LocalTerminalFont
import dev.termish.util.ioDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

private enum class HomeTab { HOSTS, CONNECTIONS, SETTINGS }

/** 极简底栏项：图标 + 等宽字体小标签，选中=主题绿，无胶囊指示器。 */
@Composable
private fun HomeTabItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    mono: FontFamily,
    modifier: Modifier = Modifier,
    badge: Int = 0,
    onClick: () -> Unit,
) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier.clickable(onClick = onClick).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BadgedBox(badge = {
            if (badge > 0) Badge { Text("$badge", fontFamily = mono) }
        }) {
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
    data class Edit(val hostId: String?) : Screen

    /** 直接持有 controller 引用：会话由 SessionManager 管理，跨页面存活。 */
    data object Terminal : Screen
}

/** 设置页二级页（统一由 AppRoot 管理开关：返回链 + 全屏 + 底部 tab 隐藏）。 */
enum class SettingsSubPage {
    TERMINAL, NOTIFICATION, DIAGNOSTICS, SNIPPETS, VOICE,
}

@Composable
fun AppRoot(repository: HostRepository) {
    // 语音识别服务旧配置（单实例 asrResourceId）迁移到 provider 列表：
    // 首次启动把旧资源 ID + 旧密钥搬进列表，避免用户重配
    fun migrateLegacyAsr(s: AppSettings): AppSettings {
        if (s.asrProviders.isNotEmpty() || s.asrResourceId.isBlank()) return s
        val legacyKey = SecretStore.get(SECRET_SERVICE, ASR_API_KEY_ACCOUNT)
        if (legacyKey.isNullOrBlank()) return s
        val p = AsrProvider(
            id = newId(),
            type = AsrProviderType.VOLC_STREAMING,
            name = "",
            resourceId = s.asrResourceId,
            enabled = true,
        )
        SecretStore.set(SECRET_SERVICE, asrKeyAccount(p.id), legacyKey)
        return s.copy(asrProviders = listOf(p))
    }

    var settings by remember { mutableStateOf(migrateLegacyAsr(repository.loadSettings())) }
    var hosts by remember { mutableStateOf(repository.listHosts()) }
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    val scope = rememberCoroutineScope()

    // 语言文案（提前声明供 connectSftp 等 lambda 使用）
    val appStrings = remember(settings.language) { appStringsFor(settings.language) }
    // 连接错误文案随语言即时切换：SessionManager 跨重组复用，须经 State 取最新值
    val currentStrings = rememberUpdatedState(appStrings)
    val sessionManager = remember { SessionManager(repository, strings = { currentStrings.value }) }

    /** 终端页当前显示的 tab（SSH 会话或 SFTP，同主机多会话切换用）。 */
    var currentTab by remember { mutableStateOf<SessionTab?>(null) }
    /** 等待连接完成后再跳转的会话（连接期间卡片头像转圈）。 */
    var pendingNavigate by remember { mutableStateOf<TerminalController?>(null) }
    /** 终端 + 菜单「收藏夹」对话框：host + 收藏路径列表（null = 关闭）。 */
    var favoritesDialog by remember { mutableStateOf<Pair<Host, List<String>>?>(null) }
    /** SFTP：选主机覆盖层 / 当前会话 / 认证与主机密钥弹窗。 */
    var sftpPickerVisible by remember { mutableStateOf(false) }
    var sftpAuth by remember { mutableStateOf<AuthPromptRequest?>(null) }
    var sftpHostKey by remember { mutableStateOf<HostKeyRequest?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    /**
     * 建立 SFTP 会话（认证/主机密钥确认走全局弹窗），成功后回调 [onEstablished]。
     * 供首次连接与断线重连复用；失败由调用方处理（首次=Snackbar，重连=保持 banner）。
     * 定义在 connectSftp 之前：局部函数不能前向引用。
     * suspend：createSftpSession 是阻塞连接，必须切 IO 线程（调用方在 Main scope，
     * 否则重连按钮点击后 UI 冻结几秒——「点了没反应」）。
     */
    suspend fun establishSftp(host: Host, onEstablished: (SftpSession, Any) -> Unit) {
        // 本次连接的身份代次标识：onClosed 凭此区分「本代连接意外断开」与
        // 「重连/换新时旧连接被主动关闭」（close 会同步触发旧回调，
        // 不比对代次就会把断开 banner 误标回刚重连成功的会话）
        val connectionToken = Any()
        val (pw, key) = resolveCredentials(host)
        val callbacks = object : SshCallbacks {
            override suspend fun onOutput(data: ByteArray) {}
            override suspend fun onStderr(data: ByteArray) {}
            override fun onExitStatus(status: Int) {}
            override fun onClosed(reason: String?) {
                // SFTP 断开主动推送：立即置 disconnected（banner 红条 + 重连入口）。
                // 代次比对：重连/换新/移除时被 close 的旧连接回调仍会触发，
                // 但条目已登记新代次（或已移除）→ 忽略，不误标
                val entry = sessionManager.sftpSessions
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
                val known = repository.getHost(host.id)?.knownHostFingerprint
                    ?: host.knownHostFingerprint
                if (known != null) {
                    if (known == hostKey.fingerprintSha256) return true
                    // 指纹变更：弹窗让用户核对新旧指纹
                    val req = HostKeyRequest(hostKey, changed = true, previousFingerprint = known)
                    sftpHostKey = req
                    val accepted = runBlocking { req.deferred.await() }
                    if (accepted) repository.touchConnected(host.id, hostKey.fingerprintSha256)
                    return accepted
                }
                // 首次连接：设置关闭首次确认则直接信任
                if (!repository.loadSettings().verifyHostKeyOnFirstUse) return true
                val req = HostKeyRequest(hostKey)
                sftpHostKey = req
                val accepted = runBlocking { req.deferred.await() }
                if (accepted) repository.touchConnected(host.id, hostKey.fingerprintSha256)
                return accepted
            }
        }
        val conn = SshConnection(
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
                    currentTab = SessionTab.Sftp(host, session, entry.uiState)
                    screen = Screen.Terminal
                }
            } catch (e: Exception) {
                snackbarHostState.showSnackbar(appStrings.sftpConnectFailed(e.message ?: ""))
            }
        }
    }
    LaunchedEffect(pendingNavigate) {
        val target = pendingNavigate ?: return@LaunchedEffect
        // TUI 会话（herdr 工作台 / 启动命令）不预连：列表页无终端画布只能
        // 80x24 起步，herdr/tmux 按错误尺寸布局后再 resize 会整体重排跳动；
        // 直接进终端页等画布量到实际尺寸后建连，首帧即正确布局
        //（纯 shell 预连无此问题——resize 只是把提示符换行）
        if (target.host.launchHerdr || target.host.startupCommand.isNotBlank()) {
            currentTab = SessionTab.Terminal(target)
            screen = Screen.Terminal
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
            screen = Screen.Terminal
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
    // Android 由前台服务保活、桌面端无此语义，对应实现为空操作。
    val disposeNetwork = observeNetworkChange { kind ->
        // 网络事件：SSH 断开/切换都重连；mosh 断网与传输切换都靠 UDP 漫游自愈，不重建
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
        val dispose = observeAppLifecycle { foreground ->
            TermLog.d("life") { "foreground=$foreground" }
            NotificationCenter.foreground = foreground
            if (foreground) {
                sessionManager.reconnectDroppedSessions()
                // 保活服务被杀（Android 15 dataSync 6h 超时等）但仍有活跃会话时，
                // 回前台立即重新拉起，避免 wakelock 缺失导致锁屏断连。
                // 只对【已连接】会话拉起：disconnect 的会话保留在列表里，误拉起会
                // 造成计数无对应 stop 的服务空转；待重连的会话由 startKeepAlive 自己拉。
                if (sessionManager.sessions.any { it.isConnected() } && !SessionKeepAlive.isActive()) {
                    SessionKeepAlive.onSessionStart()
                }
            } else {
                sessionManager.noteBackgrounded()
                // 退后台即保存最新 SFTP 浏览路径：杀 App 重进后恢复到上次目录
                sessionManager.persistNow()
            }
        }
        onDispose {
            dispose()
            disposeNetwork()
        }
    }

    // 全局返回栈：非主页 → 回主页；设置二级页 → 关二级页回设置；主页非主机 tab → 回主机 tab
    //（二级页状态必须提升到这里：此前藏在 SettingsScreen 内部，系统返回键/手势
    // 直接跳回主机 tab——二级页开着却无处返回）
    var homeTab by remember { mutableStateOf(HomeTab.HOSTS) }
    var settingsSubPage by remember { mutableStateOf<SettingsSubPage?>(null) }
    PlatformBackHandler(enabled = screen != Screen.Home || settingsSubPage != null || homeTab != HomeTab.HOSTS) {
        when {
            screen != Screen.Home -> screen = Screen.Home
            settingsSubPage != null -> settingsSubPage = null
            else -> homeTab = HomeTab.HOSTS
        }
    }

    fun refreshHosts() {
        hosts = repository.listHosts()
    }

    val terminalTheme = TerminalThemes.ALL.getOrElse(settings.terminalThemeIndex) { TerminalThemes.ALL[0] }

    CompositionLocalProvider(
        LocalAppStrings provides appStrings,
        LocalTerminalFont provides TerminalFont.byId(settings.terminalFontId),
    ) {
        TermishTheme(settings.theme) {
            // 状态栏图标颜色只看当前页面实际背景亮度：
            // - 终端 tab → 按终端主题背景亮度
            // - 其余页面 → 按应用主题亮度
            val pageDark = if (screen is Screen.Terminal && currentTab is SessionTab.Terminal) {
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
            when (val s = screen) {
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
                                        Modifier.fillMaxWidth()
                                            .background(MaterialTheme.colorScheme.surface)
                                            .navigationBarsPadding(),
                                    ) {
                                        HomeTabItem(appStrings.appTabHosts, Icons.Default.Dns, homeTab == HomeTab.HOSTS, mono, Modifier.weight(1f)) {
                                            homeTab = HomeTab.HOSTS
                                        }
                                        HomeTabItem(appStrings.appTabConnections, Icons.Default.Cable, homeTab == HomeTab.CONNECTIONS, mono, Modifier.weight(1f), badge = sessionManager.sessions.size) {
                                            homeTab = HomeTab.CONNECTIONS
                                        }
                                        HomeTabItem(appStrings.appTabSettings, Icons.Default.Settings, homeTab == HomeTab.SETTINGS, mono, Modifier.weight(1f)) {
                                            homeTab = HomeTab.SETTINGS
                                        }
                                    }
                                }
                            }
                        },
                    ) { padding ->
                        Box(Modifier.padding(padding)) {
                            when (homeTab) {
                                HomeTab.HOSTS -> HostListScreen(
                                    hosts = hosts,
                                    hostSessions = (
                                        sessionManager.sessions.map { HostSessionItem.Terminal(it) } +
                                            sessionManager.sftpSessions.map { HostSessionItem.Sftp(it.host, it.session) }
                                        ).groupBy { it.hostId },
                                    onAdd = { screen = Screen.Edit(null) },
                                    onEdit = { screen = Screen.Edit(it.id) },
                                    onConnect = { host ->
                                        // 防重复：已有「连接中」会话（转圈期间再点卡片）直接进入，不新建；
                                        // 但配置/凭据已变更的旧会话不复用（用当前配置新建）
                                        val connecting = sessionManager.sessions.firstOrNull {
                                            it.host.id == host.id &&
                                                (it.status == ConnStatus.CONNECTING || it.status == ConnStatus.AUTH) &&
                                                it.credentialKey == sessionManager.signatureFor(host)
                                        }
                                        if (connecting != null) {
                                            currentTab = SessionTab.Terminal(connecting)
                                            screen = Screen.Terminal
                                        } else {
                                            val controller = sessionManager.open(host, settings.autoReconnect) {
                                                hosts = repository.listHosts()
                                            }
                                            // 先留在列表：卡片头像转圈，连接完成后再跳转
                                            pendingNavigate = controller
                                        }
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
                                    },
                                    onOpenSession = { controller ->
                                        // 卡片点击 = 用当前配置连这台主机：配置/凭据已变更的旧会话不复用，
                                        // 用当前配置新建（旧会话保留在连接页，可手动关闭/重入）
                                        if (controller.credentialKey != sessionManager.signatureFor(controller.host)) {
                                            val fresh = sessionManager.open(controller.host, settings.autoReconnect) {
                                                hosts = repository.listHosts()
                                            }
                                            pendingNavigate = fresh
                                        } else {
                                            currentTab = SessionTab.Terminal(controller)
                                            screen = Screen.Terminal
                                        }
                                    },
                                    onOpenSftp = { host, session ->
                                        // 用 entry 的 uiState（若已存在）：重新进入不重置浏览状态/路径
                                        val entry = sessionManager.sftpSessions.firstOrNull { it.host.id == host.id }
                                        currentTab = SessionTab.Sftp(host, session, entry?.uiState ?: SftpUiState())
                                        screen = Screen.Terminal
                                    },
                                    onCloseAllSessions = { host ->
                                        // 关闭该主机全部会话：终端断开保留 + SFTP 释放
                                        sessionManager.closeAllForHost(host.id)
                                    },
                                    onDelete = { host ->
                                        sessionManager.closeForHost(host.id)
                                        SecretStore.delete(SECRET_SERVICE, secretAccountFor(host.id, "password"))
                                        SecretStore.delete(SECRET_SERVICE, secretAccountFor(host.id, "privateKey"))
                                        repository.deleteHost(host.id)
                                        refreshHosts()
                                    },
                                )

                                HomeTab.CONNECTIONS -> ConnectionsScreen(
                                    sessions = sessionManager.sessions.map { HostSessionItem.Terminal(it) } +
                                        sessionManager.sftpSessions.map { HostSessionItem.Sftp(it.host, it.session) },
                                    onOpen = { item ->
                                        when (item) {
                                            is HostSessionItem.Terminal -> {
                                                currentTab = SessionTab.Terminal(item.controller)
                                                screen = Screen.Terminal
                                            }
                                            is HostSessionItem.Sftp -> {
                                                // 连接页重入：用 entry 的 uiState（浏览状态/路径保留）
                                                val entry = sessionManager.sftpSessions.firstOrNull {
                                                    it.host.id == item.host.id && it.session === item.session
                                                }
                                                currentTab = SessionTab.Sftp(item.host, item.session, entry?.uiState ?: SftpUiState())
                                                screen = Screen.Terminal
                                            }
                                        }
                                    },
                                    onClose = { item ->
                                        when (item) {
                                            is HostSessionItem.Terminal -> {
                                                if (item.isActive) sessionManager.disconnect(item.controller)
                                                else sessionManager.remove(item.controller)
                                            }
                                            is HostSessionItem.Sftp -> {
                                                // 与终端同语义两段式：活跃=断开保留（重连恢复路径），
                                                // 已断开（session=null）=从列表移除
                                                val entry = sessionManager.sftpSessions
                                                    .firstOrNull { it.session === item.session || (item.session == null && it.host.id == item.host.id) }
                                                when {
                                                    entry == null -> {}
                                                    entry.session != null -> sessionManager.disconnectSftp(entry)
                                                    else -> sessionManager.closeSftp(entry)
                                                }
                                            }
                                        }
                                    },
                                )

                                HomeTab.SETTINGS -> SettingsScreen(
                                    settings = settings,
                                    onChange = { new ->
                                        // 即改即存
                                        repository.saveSettings(new)
                                        settings = new
                                    },
                                    repository = repository,
                                    subPage = settingsSubPage,
                                    onOpenSub = { settingsSubPage = it },
                                )
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
                            if (pw.isNotBlank()) SecretStore.set(SECRET_SERVICE, secretAccountFor(host.id, "password"), pw)
                            if (key.isNotBlank()) SecretStore.set(SECRET_SERVICE, secretAccountFor(host.id, "privateKey"), key)
                            repository.upsertHost(host)
                            refreshHosts()
                            screen = Screen.Home
                        },
                        onCancel = { screen = Screen.Home },
                    )
                }

                // 返回主页不断开：默认后台运行，会话保留在 SessionManager，
                // 由前台服务保活，从「连接」页可重新进入（终端缓冲原样保留）
                // 终端页 tabs = 全部会话（跨主机）：连任何主机都进同一个终端页，
                // tab 栏以「user@host + 状态点」区分（Termius 式全局会话）
                is Screen.Terminal -> {
                    // 终端会话 tab 不过滤状态：断开/失败也保留（tab 内状态点体现），
                    // 关闭 tab 时才从列表移除；否则创建 SFTP 后重组会把非活跃终端 tab 丢掉
                    val terminalTabs = sessionManager.sessions.map { SessionTab.Terminal(it) }
                    val sftpTabs = sessionManager.sftpSessions
                        .map { SessionTab.Sftp(it.host, it.session, it.uiState) }
                    val current = currentTab?.takeIf { tab ->
                        tab.id in (terminalTabs.map { it.id } + sftpTabs.map { it.id })
                    } ?: (terminalTabs + sftpTabs).firstOrNull()
                    if (current != null) {
                        val tabs = terminalTabs + sftpTabs
                        // 「+」新增会话归属：当前选中会话的主机（SFTP tab 用其主机）
                        val currentHost = when (current) {
                            is SessionTab.Terminal -> current.controller.host
                            is SessionTab.Sftp -> current.host
                        }
                        TerminalScreen(
                            tabs = tabs,
                            current = current,
                            theme = terminalTheme,
                            settings = settings,
                            repository = repository,
                            onBack = {
                                currentTab = null
                                refreshHosts()
                                screen = Screen.Home
                            },
                            onSwitchTab = { currentTab = it },
                            onAddSession = {
                                val c = sessionManager.open(currentHost, settings.autoReconnect) {
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
                                }
                                val remaining = (sessionManager.sessions
                                    .map { SessionTab.Terminal(it) } +
                                    sessionManager.sftpSessions
                                        .map { SessionTab.Sftp(it.host, it.session, it.uiState) })
                                    .firstOrNull { it.id != tab.id }
                                currentTab = remaining
                                if (remaining == null) {
                                    refreshHosts()
                                    screen = Screen.Home
                                }
                            },
                            onOpenSftpPicker = { sftpPickerVisible = true },
                            // 文件管理：直接对当前主机建 SFTP 会话并切到 SFTP tab
                            //（复用 connectSftp 全流程：认证弹窗 / 主机密钥 / 断线重连）
                            onOpenSftpForHost = connectSftp,
                            // 收藏夹：读取持久化收藏，弹列表跳转（无收藏则提示）
                            onOpenFavorites = { host ->
                                val favs = repository.loadFavorites(host.id)
                                if (favs.isEmpty()) {
                                    scope.launch { snackbarHostState.showSnackbar(appStrings.sftpExt.favoritesEmpty) }
                                } else {
                                    favoritesDialog = host to favs
                                }
                            },
                            // 收藏变更：SFTP 页增删收藏即落盘
                            onFavoritesChanged = { host, favs ->
                                repository.saveFavorites(host.id, favs)
                            },
                            // 浏览路径即时持久化：导航即保存（退后台保存为兜底）
                            onSftpPathChanged = { _, _ -> sessionManager.persistNow() },
                            // SFTP 断线重连：重建会话替换 tab（保留 uiState 的路径/列表）
                            onReconnectSftp = { tab ->
                                // session 可空（进程重启恢复条目）：host.id + 引用双重匹配，
                                // 避免多个 null-session 条目时错配
                                val entry = sessionManager.sftpSessions.find {
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
                AlertDialog(
                    onDismissRequest = { favoritesDialog = null },
                    title = { Text(appStrings.sftpExt.favoritesTitle) },
                    text = {
                        Column {
                            favs.forEach { fav ->
                                Row(
                                    Modifier.fillMaxWidth().clickable {
                                        favoritesDialog = null
                                        connectSftp(host, fav)
                                    }.padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Filled.FolderOpen,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Text(
                                        fav,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                                    )
                                    IconButton(
                                        onClick = {
                                            val remaining = favs - fav
                                            favoritesDialog = host to remaining
                                            repository.saveFavorites(host.id, remaining)
                                        },
                                        modifier = Modifier.size(28.dp),
                                    ) {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(14.dp),
                                        )
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { favoritesDialog = null }) { Text(appStrings.terminalCancel) }
                    },
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

            TermishSnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

/** 从安全存储解析认证凭据。 */
fun resolveCredentials(host: Host): Pair<String?, String?> {
    val pw = SecretStore.get(SECRET_SERVICE, secretAccountFor(host.id, "password"))
    val key = SecretStore.get(SECRET_SERVICE, secretAccountFor(host.id, "privateKey"))
    return when (host.authMethod) {
        HostAuthMethod.PASSWORD -> pw to null
        HostAuthMethod.PRIVATE_KEY -> null to key
        HostAuthMethod.KEY_OR_PASSWORD -> key to pw
    }
}

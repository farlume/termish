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
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.termish.data.Host
import dev.termish.data.HostRepository
import dev.termish.data.SECRET_SERVICE
import dev.termish.data.SecretStore
import dev.termish.data.ThemeMode
import dev.termish.data.secretAccountFor
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
    data class Terminal(val hostId: String) : Screen
}

@Composable
fun AppRoot(repository: HostRepository) {
    var settings by remember { mutableStateOf(repository.loadSettings()) }
    var hosts by remember { mutableStateOf(repository.listHosts()) }
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    val scope = rememberCoroutineScope()
    val sessionManager = remember { SessionManager(repository) }
    /** 终端页当前显示的 tab（SSH 会话或 SFTP，同主机多会话切换用）。 */
    var currentTab by remember { mutableStateOf<SessionTab?>(null) }
    /** 等待连接完成后再跳转的会话（连接期间卡片头像转圈）。 */
    var pendingNavigate by remember { mutableStateOf<TerminalController?>(null) }
    /** SFTP：选主机覆盖层 / 当前会话 / 认证与主机密钥弹窗。 */
    var sftpPickerVisible by remember { mutableStateOf(false) }
    var sftpAuth by remember { mutableStateOf<AuthPromptRequest?>(null) }
    var sftpHostKey by remember { mutableStateOf<HostKeyRequest?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    // 语言文案（提前声明供 connectSftp 等 lambda 使用）
    val appStrings = remember(settings.language) { appStringsFor(settings.language) }

    /**
     * 建立 SFTP 会话（认证/主机密钥确认走全局弹窗），成功后回调 [onEstablished]。
     * 供首次连接与断线重连复用；失败由调用方处理（首次=Snackbar，重连=保持 banner）。
     * 定义在 connectSftp 之前：局部函数不能前向引用。
     * suspend：createSftpSession 是阻塞连接，必须切 IO 线程（调用方在 Main scope，
     * 否则重连按钮点击后 UI 冻结几秒——「点了没反应」）。
     */
    suspend fun establishSftp(host: Host, onEstablished: (SftpSession) -> Unit) {
        val (pw, key) = resolveCredentials(host)
        val callbacks = object : SshCallbacks {
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
        onEstablished(session)
    }

    // 覆盖层选主机后：建立 SFTP 会话（认证/主机密钥弹窗走全局 sftpAuth/sftpHostKey）
    val connectSftp: (Host) -> Unit = { host ->
        sftpPickerVisible = false
        scope.launch {
            try {
                establishSftp(host) { session ->
                    val entry = sessionManager.addSftp(host, session)
                    // 与 entry 共用同一 uiState：浏览路径变化能反映到持久化（退后台保存）
                    currentTab = SessionTab.Sftp(host, session, entry.uiState)
                    screen = Screen.Terminal(host.id)
                }
            } catch (e: Exception) {
                snackbarHostState.showSnackbar(appStrings.sftpConnectFailed(e.message ?: ""))
            }
        }
    }
    LaunchedEffect(pendingNavigate) {
        val target = pendingNavigate ?: return@LaunchedEffect
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
            screen = Screen.Terminal(target.host.id)
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
        val dispose = observeAppLifecycle { foreground ->
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

    // 全局返回栈：非主页 → 回主页；主页非主机 tab → 回主机 tab；否则交给系统退出
    var homeTab by remember { mutableStateOf(HomeTab.HOSTS) }
    PlatformBackHandler(enabled = screen != Screen.Home || homeTab != HomeTab.HOSTS) {
        if (screen != Screen.Home) screen = Screen.Home else homeTab = HomeTab.HOSTS
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
                                            screen = Screen.Terminal(host.id)
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
                                        sessionManager.sessions
                                            .firstOrNull { it.host.id == host.id }
                                            ?.let { sessionManager.disconnect(it) }
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
                                            screen = Screen.Terminal(controller.host.id)
                                        }
                                    },
                                    onOpenSftp = { host, session ->
                                        // 用 entry 的 uiState（若已存在）：重新进入不重置浏览状态/路径
                                        val entry = sessionManager.sftpSessions.firstOrNull { it.host.id == host.id }
                                        currentTab = SessionTab.Sftp(host, session, entry?.uiState ?: SftpUiState())
                                        screen = Screen.Terminal(host.id)
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
                                                screen = Screen.Terminal(item.controller.host.id)
                                            }
                                            is HostSessionItem.Sftp -> {
                                                // 连接页重入：用 entry 的 uiState（浏览状态/路径保留）
                                                val entry = sessionManager.sftpSessions.firstOrNull {
                                                    it.host.id == item.host.id && it.session === item.session
                                                }
                                                currentTab = SessionTab.Sftp(item.host, item.session, entry?.uiState ?: SftpUiState())
                                                screen = Screen.Terminal(item.host.id)
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
                                                sessionManager.sftpSessions
                                                    .firstOrNull { it.session === item.session }
                                                    ?.let { sessionManager.closeSftp(it) }
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
                                )
                            }
                        }
                    }
                }

                is Screen.Edit -> {
                    val existing = hosts.firstOrNull { it.id == s.hostId }
                    HostEditScreen(
                        existing = existing,
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
                is Screen.Terminal -> {
                    val host = hosts.firstOrNull { it.id == s.hostId }
                    val all = sessionManager.sessions.filter { it.host.id == s.hostId }
                    val sftpTabs = sessionManager.sftpSessions
                        .filter { it.host.id == s.hostId }
                        .map { SessionTab.Sftp(it.host, it.session, it.uiState) }
                    // 终端会话 tab 不过滤状态：断开/失败也保留（tab 内状态点体现），
                    // 关闭 tab 时才从列表移除；否则创建 SFTP 后重组会把非活跃终端 tab 丢掉
                    val terminalTabs = all.map { SessionTab.Terminal(it) }
                    val current = currentTab?.takeIf { tab ->
                        tab.id in (terminalTabs.map { it.id } + sftpTabs.map { it.id })
                    } ?: (terminalTabs + sftpTabs).firstOrNull()
                    if (host != null && current != null) {
                        val tabs = terminalTabs + sftpTabs
                        TerminalScreen(
                            tabs = tabs,
                            current = current,
                            theme = terminalTheme,
                            settings = settings,
                            onBack = {
                                currentTab = null
                                refreshHosts()
                                screen = Screen.Home
                            },
                            onSwitchTab = { currentTab = it },
                            onAddSession = {
                                val c = sessionManager.open(host, settings.autoReconnect) {
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
                                    .filter { c: TerminalController -> c.host.id == s.hostId }
                                    .map { c: TerminalController -> SessionTab.Terminal(c) } +
                                    sessionManager.sftpSessions
                                        .filter { it.host.id == s.hostId }
                                        .map { SessionTab.Sftp(it.host, it.session, it.uiState) })
                                    .firstOrNull { it.id != tab.id }
                                currentTab = remaining
                                if (remaining == null) {
                                    refreshHosts()
                                    screen = Screen.Home
                                }
                            },
                            onOpenSftpPicker = { sftpPickerVisible = true },
                            // SFTP 断线重连：重建会话替换 tab（保留 uiState 的路径/列表）
                            onReconnectSftp = { tab ->
                                // session 可空（进程重启恢复条目）：host.id + 引用双重匹配，
                                // 避免多个 null-session 条目时错配
                                val entry = sessionManager.sftpSessions.find {
                                    it.host.id == tab.host.id && it.session === tab.session
                                }
                                scope.launch {
                                    try {
                                        establishSftp(tab.host) { newSession ->
                                            entry?.let { sessionManager.reconnectSftp(it, newSession) }
                                            currentTab = SessionTab.Sftp(tab.host, newSession, tab.uiState)
                                            // 重连成功：清除重连中状态，SftpContent 继续用原路径浏览
                                            tab.uiState.reconnecting = false
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
                    onSelect = connectSftp,
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

            SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

/** 从安全存储解析认证凭据。 */
fun resolveCredentials(host: Host): Pair<String?, String?> {
    val pw = SecretStore.get(SECRET_SERVICE, secretAccountFor(host.id, "password"))
    val key = SecretStore.get(SECRET_SERVICE, secretAccountFor(host.id, "privateKey"))
    return when (host.authMethod) {
        dev.termish.data.HostAuthMethod.PASSWORD -> pw to null
        dev.termish.data.HostAuthMethod.PRIVATE_KEY -> null to key
        dev.termish.data.HostAuthMethod.KEY_OR_PASSWORD -> key to pw
    }
}

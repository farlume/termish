package dev.mssh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.mssh.data.Host
import dev.mssh.data.HostRepository
import dev.mssh.data.SECRET_SERVICE
import dev.mssh.data.SecretStore
import dev.mssh.data.secretAccountFor
import dev.mssh.ui.theme.MsshTheme
import dev.mssh.ui.theme.TerminalThemes
import dev.mssh.util.monospaceFontFamily
import dev.mssh.util.observeAppLifecycle

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
    data class Terminal(val controller: TerminalController) : Screen
}

@Composable
fun AppRoot(repository: HostRepository) {
    var settings by remember { mutableStateOf(repository.loadSettings()) }
    var hosts by remember { mutableStateOf(repository.listHosts()) }
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    val sessionManager = remember { SessionManager(repository) }
    // 恢复上次运行时的会话列表（仅一次；进程死亡连接必死，恢复为未连接可重连）
    var sessionsRestored by remember { mutableStateOf(false) }
    if (!sessionsRestored) {
        sessionManager.restoreRecent(hosts, settings.autoReconnect)
        sessionsRestored = true
    }

    // iOS：退到桌面后系统挂起进程、掐断 socket；回前台时自动重连活跃会话（缓冲保留）。
    // Android 由前台服务保活、桌面端无此语义，对应实现为空操作。
    DisposableEffect(Unit) {
        val dispose = observeAppLifecycle { foreground ->
            if (foreground) {
                sessionManager.reconnectDroppedSessions()
            } else {
                sessionManager.noteBackgrounded()
            }
        }
        onDispose { dispose() }
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

    // 语言设置：跟随系统或用户显式选择，全树文案由 LocalAppStrings 提供
    val appStrings = remember(settings.language) { appStringsFor(settings.language) }

    CompositionLocalProvider(LocalAppStrings provides appStrings) {
        MsshTheme(settings.theme) {
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
                                    onAdd = { screen = Screen.Edit(null) },
                                    onEdit = { screen = Screen.Edit(it.id) },
                                    onConnect = { host ->
                                        val controller = sessionManager.open(host, settings.autoReconnect)
                                        sessionManager.cancelScheduledClose(controller)
                                        screen = Screen.Terminal(controller)
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
                                    sessions = sessionManager.sessions,
                                    onOpen = {
                                        sessionManager.cancelScheduledClose(it)
                                        screen = Screen.Terminal(it)
                                    },
                                    onClose = {
                                        // 活跃会话 → 断开（保留列表）；已断开 → 移除
                                        if (it.status == ConnStatus.CONNECTED || it.status == ConnStatus.CONNECTING || it.status == ConnStatus.AUTH) {
                                            sessionManager.disconnect(it)
                                        } else {
                                            sessionManager.remove(it)
                                        }
                                        refreshHosts()
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

                // 返回主页不断开：会话保留在 SessionManager，由前台服务保活，
                // 从「连接」页可重新进入（终端缓冲原样保留）
                is Screen.Terminal -> TerminalScreen(
                    controller = s.controller,
                    theme = terminalTheme,
                    settings = settings,
                    onBack = {
                        refreshHosts()
                        screen = Screen.Home
                    },
                    onBackWithPolicy = { policy ->
                        when (policy) {
                            SessionKeepPolicy.KEEP_10_MIN ->
                                sessionManager.scheduleClose(s.controller, 10 * 60 * 1000L)
                            SessionKeepPolicy.DISCONNECT -> sessionManager.disconnect(s.controller)
                            SessionKeepPolicy.KEEP_ALIVE -> {}
                        }
                        refreshHosts()
                        screen = Screen.Home
                    },
                )
            }
        }
    }
}

/** 从安全存储解析认证凭据。 */
fun resolveCredentials(host: Host): Pair<String?, String?> {
    val pw = SecretStore.get(SECRET_SERVICE, secretAccountFor(host.id, "password"))
    val key = SecretStore.get(SECRET_SERVICE, secretAccountFor(host.id, "privateKey"))
    return when (host.authMethod) {
        dev.mssh.data.HostAuthMethod.PASSWORD -> pw to null
        dev.mssh.data.HostAuthMethod.PRIVATE_KEY -> null to key
        dev.mssh.data.HostAuthMethod.KEY_OR_PASSWORD -> key to pw
    }
}

package dev.mssh.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.mssh.data.SECRET_SERVICE
import dev.mssh.data.SecretStore
import dev.mssh.data.Host
import dev.mssh.data.HostRepository
import dev.mssh.data.secretAccountFor
import dev.mssh.ui.theme.MsshTheme
import dev.mssh.ui.theme.TerminalThemes

private enum class HomeTab { HOSTS, CONNECTIONS, SETTINGS }

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

    fun refreshHosts() {
        hosts = repository.listHosts()
    }

    val terminalTheme = TerminalThemes.ALL.getOrElse(settings.terminalThemeIndex) { TerminalThemes.ALL[0] }

    MsshTheme(settings.theme) {
        when (val s = screen) {
            Screen.Home -> {
                var tab by remember { mutableStateOf(HomeTab.HOSTS) }
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = tab == HomeTab.HOSTS,
                                onClick = { tab = HomeTab.HOSTS },
                                icon = { Icon(Icons.Default.Dns, contentDescription = "主机") },
                                label = { Text("主机") },
                            )
                            NavigationBarItem(
                                selected = tab == HomeTab.CONNECTIONS,
                                onClick = { tab = HomeTab.CONNECTIONS },
                                icon = {
                                    BadgedBox(badge = {
                                        if (sessionManager.sessions.isNotEmpty()) {
                                            Badge { Text("${sessionManager.sessions.size}") }
                                        }
                                    }) {
                                        Icon(Icons.Default.Cable, contentDescription = "连接")
                                    }
                                },
                                label = { Text("连接") },
                            )
                            NavigationBarItem(
                                selected = tab == HomeTab.SETTINGS,
                                onClick = { tab = HomeTab.SETTINGS },
                                icon = { Icon(Icons.Default.Settings, contentDescription = "设置") },
                                label = { Text("设置") },
                            )
                        }
                    },
                ) { padding ->
                    Box(Modifier.padding(padding)) {
                        when (tab) {
                            HomeTab.HOSTS -> HostListScreen(
                                hosts = hosts,
                                onAdd = { screen = Screen.Edit(null) },
                                onEdit = { screen = Screen.Edit(it.id) },
                                onConnect = { host ->
                                    val controller = sessionManager.open(host, settings.autoReconnect)
                                    screen = Screen.Terminal(controller)
                                },
                                onDelete = { host ->
                                    sessionManager.closeForHost(host.id)
                                    SecretStore.delete(SECRET_SERVICE, secretAccountFor(host.id, "password"))
                                    SecretStore.delete(SECRET_SERVICE, secretAccountFor(host.id, "privateKey"))
                                    repository.deleteHost(host.id)
                                    refreshHosts()
                                },
                                onOpenSettings = { tab = HomeTab.SETTINGS },
                            )

                            HomeTab.CONNECTIONS -> ConnectionsScreen(
                                sessions = sessionManager.sessions,
                                onOpen = { screen = Screen.Terminal(it) },
                                onClose = {
                                    sessionManager.close(it)
                                    refreshHosts()
                                },
                            )

                            HomeTab.SETTINGS -> SettingsScreen(
                                settings = settings,
                                onSave = { new ->
                                    repository.saveSettings(new)
                                    settings = new
                                },
                                onCancel = { tab = HomeTab.HOSTS },
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
            )
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

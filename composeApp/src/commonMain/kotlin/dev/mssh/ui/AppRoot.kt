package dev.mssh.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.mssh.data.AppSettings
import dev.mssh.data.Host
import dev.mssh.data.HostRepository
import dev.mssh.data.SECRET_SERVICE
import dev.mssh.data.SecretStore
import dev.mssh.data.secretAccountFor
import dev.mssh.ui.theme.MsshTheme
import dev.mssh.ui.theme.TerminalThemes

private sealed interface Screen {
    data object HostList : Screen
    data class Edit(val hostId: String?) : Screen
    data class Terminal(val hostId: String, val password: String?, val privateKey: String?) : Screen
    data object Settings : Screen
}

@Composable
fun AppRoot(repository: HostRepository) {
    var settings by remember { mutableStateOf(repository.loadSettings()) }
    var hosts by remember { mutableStateOf(repository.listHosts()) }
    var screen by remember { mutableStateOf<Screen>(Screen.HostList) }

    fun refreshHosts() {
        hosts = repository.listHosts()
    }

    val terminalTheme = TerminalThemes.ALL.getOrElse(settings.terminalThemeIndex) { TerminalThemes.ALL[0] }

    MsshTheme(settings.theme) {
        when (val s = screen) {
            Screen.HostList -> HostListScreen(
                hosts = hosts,
                onAdd = { screen = Screen.Edit(null) },
                onEdit = { screen = Screen.Edit(it.id) },
                onConnect = { host ->
                    val (pw, key) = resolveCredentials(host)
                    screen = Screen.Terminal(host.id, pw, key)
                },
                onDelete = { host ->
                    SecretStore.delete(SECRET_SERVICE, secretAccountFor(host.id, "password"))
                    SecretStore.delete(SECRET_SERVICE, secretAccountFor(host.id, "privateKey"))
                    repository.deleteHost(host.id)
                    refreshHosts()
                },
                onOpenSettings = { screen = Screen.Settings },
            )

            is Screen.Edit -> {
                val existing = hosts.firstOrNull { it.id == s.hostId }
                HostEditScreen(
                    existing = existing,
                    onSave = { host, pw, key ->
                        if (pw.isNotBlank()) SecretStore.set(SECRET_SERVICE, secretAccountFor(host.id, "password"), pw)
                        if (key.isNotBlank()) SecretStore.set(SECRET_SERVICE, secretAccountFor(host.id, "privateKey"), key)
                        repository.upsertHost(host)
                        refreshHosts()
                        screen = Screen.HostList
                    },
                    onCancel = { screen = Screen.HostList },
                )
            }

            is Screen.Terminal -> {
                val host = hosts.firstOrNull { it.id == s.hostId }
                if (host == null) {
                    screen = Screen.HostList
                } else {
                    val controller = remember(s.hostId) {
                        TerminalController(host, s.password, s.privateKey, repository, settings.autoReconnect)
                    }
                    TerminalScreen(
                        controller = controller,
                        theme = terminalTheme,
                        settings = settings,
                        onBack = {
                            controller.close()
                            refreshHosts()
                            screen = Screen.HostList
                        },
                    )
                }
            }

            Screen.Settings -> SettingsScreen(
                settings = settings,
                onSave = { new ->
                    repository.saveSettings(new)
                    settings = new
                    screen = Screen.HostList
                },
                onCancel = { screen = Screen.HostList },
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

package dev.mssh.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.mssh.data.ConnectionMode
import dev.mssh.data.Host
import dev.mssh.data.HostAuthMethod
import dev.mssh.data.QuickCommand
import dev.mssh.data.newId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostEditScreen(
    existing: Host?,
    onSave: (Host, password: String, privateKey: String) -> Unit,
    onCancel: () -> Unit,
) {
    val s = LocalAppStrings.current
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var hostname by remember { mutableStateOf(existing?.hostname ?: "") }
    var port by remember { mutableStateOf((existing?.port ?: 22).toString()) }
    var username by remember { mutableStateOf(existing?.username ?: "root") }
    var authMethod by remember { mutableStateOf(existing?.authMethod ?: HostAuthMethod.PASSWORD) }
    var connectionMode by remember { mutableStateOf(existing?.connectionMode ?: ConnectionMode.SSH) }
    var password by remember { mutableStateOf("") }
    var privateKey by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf(existing?.tags?.joinToString(",") ?: "") }
    var quickCommands by remember {
        mutableStateOf(existing?.quickCommands?.joinToString("\n") { "${it.label}:${it.command}" } ?: "")
    }
    var startupCommand by remember { mutableStateOf(existing?.startupCommand ?: "") }

    Scaffold(
        topBar = {
            MsshHeader(
                title = if (existing == null) s.editAddTitle else s.editEditTitle,
                onBack = onCancel,
                actions = {
                    TextButton(onClick = {
                        val id = existing?.id ?: newId()
                        val host = Host(
                            id = id,
                            name = name.ifBlank { hostname },
                            hostname = hostname.trim(),
                            port = port.toIntOrNull() ?: 22,
                            username = username.ifBlank { "root" },
                            authMethod = authMethod,
                            connectionMode = connectionMode,
                            tags = tags.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                            quickCommands = parseQuickCommands(quickCommands),
                            createdAt = existing?.createdAt ?: kotlinx.datetime.Clock.System.now().toEpochMilliseconds(),
                            lastConnectedAt = existing?.lastConnectedAt ?: 0L,
                            knownHostFingerprint = existing?.knownHostFingerprint,
                            startupCommand = startupCommand.trim(),
                        )
                        onSave(host, password, privateKey)
                    }) { Text(s.editSave) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(s.editName) }, singleLine = true)
            OutlinedTextField(hostname, { hostname = it }, Modifier.fillMaxWidth(), label = { Text(s.editHostname) }, singleLine = true)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(port, { port = it }, Modifier.weight(1f), label = { Text(s.editPort) }, singleLine = true)
                OutlinedTextField(username, { username = it }, Modifier.weight(2f), label = { Text(s.editUsername) }, singleLine = true)
            }

            Text(s.editAuthMethod, style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                RadioButton(authMethod == HostAuthMethod.PASSWORD, { authMethod = HostAuthMethod.PASSWORD })
                Text(s.editAuthPassword)
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                RadioButton(authMethod == HostAuthMethod.PRIVATE_KEY, { authMethod = HostAuthMethod.PRIVATE_KEY })
                Text(s.editAuthKey)
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                RadioButton(authMethod == HostAuthMethod.KEY_OR_PASSWORD, { authMethod = HostAuthMethod.KEY_OR_PASSWORD })
                Text(s.editAuthKeyOrPassword)
            }

            Text(s.editConnectionMode, style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                RadioButton(connectionMode == ConnectionMode.SSH, { connectionMode = ConnectionMode.SSH })
                Text(s.editModeSsh)
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                RadioButton(connectionMode == ConnectionMode.MOSH, { connectionMode = ConnectionMode.MOSH })
                Text(s.editModeMosh)
            }

            if (authMethod != HostAuthMethod.PRIVATE_KEY) {
                OutlinedTextField(
                    password, { password = it }, Modifier.fillMaxWidth(),
                    label = { Text(if (existing == null) s.editPassword else s.editPasswordKeep) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
            }
            if (authMethod != HostAuthMethod.PASSWORD) {
                OutlinedTextField(
                    privateKey, { privateKey = it }, Modifier.fillMaxWidth(),
                    label = { Text(if (existing == null) s.editPrivateKey else s.editPrivateKeyKeep) },
                    minLines = 3,
                )
            }

            OutlinedTextField(tags, { tags = it }, Modifier.fillMaxWidth(), label = { Text(s.editTags) }, singleLine = true)
            OutlinedTextField(
                quickCommands, { quickCommands = it }, Modifier.fillMaxWidth(),
                label = { Text(s.editQuickCommands) },
                minLines = 3,
            )
            OutlinedTextField(
                startupCommand, { startupCommand = it }, Modifier.fillMaxWidth(),
                label = { Text(s.editStartupCommand) },
                placeholder = { Text(s.editStartupPlaceholder) },
                singleLine = true,
            )
        }
    }
}

private fun parseQuickCommands(raw: String): List<QuickCommand> =
    raw.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && it.contains(":") }
        .map { line ->
            val idx = line.indexOf(':')
            QuickCommand(newId(), line.substring(0, idx).trim(), line.substring(idx + 1).trim())
        }
        .toList()

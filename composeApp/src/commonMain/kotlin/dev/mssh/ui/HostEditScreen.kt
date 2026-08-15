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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
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
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var hostname by remember { mutableStateOf(existing?.hostname ?: "") }
    var port by remember { mutableStateOf((existing?.port ?: 22).toString()) }
    var username by remember { mutableStateOf(existing?.username ?: "root") }
    var authMethod by remember { mutableStateOf(existing?.authMethod ?: HostAuthMethod.PASSWORD) }
    var password by remember { mutableStateOf("") }
    var privateKey by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf(existing?.tags?.joinToString(",") ?: "") }
    var quickCommands by remember {
        mutableStateOf(existing?.quickCommands?.joinToString("\n") { "${it.label}:${it.command}" } ?: "")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "添加主机" else "编辑主机") },
                navigationIcon = { TextButton(onClick = onCancel) { Text("取消") } },
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
                            tags = tags.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                            quickCommands = parseQuickCommands(quickCommands),
                            createdAt = existing?.createdAt ?: kotlinx.datetime.Clock.System.now().toEpochMilliseconds(),
                            lastConnectedAt = existing?.lastConnectedAt ?: 0L,
                            knownHostFingerprint = existing?.knownHostFingerprint,
                        )
                        onSave(host, password, privateKey)
                    }) { Text("保存") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("名称") }, singleLine = true)
            OutlinedTextField(hostname, { hostname = it }, Modifier.fillMaxWidth(), label = { Text("主机地址") }, singleLine = true)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(port, { port = it }, Modifier.weight(1f), label = { Text("端口") }, singleLine = true)
                OutlinedTextField(username, { username = it }, Modifier.weight(2f), label = { Text("用户名") }, singleLine = true)
            }

            Text("认证方式", style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                RadioButton(authMethod == HostAuthMethod.PASSWORD, { authMethod = HostAuthMethod.PASSWORD })
                Text("密码")
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                RadioButton(authMethod == HostAuthMethod.PRIVATE_KEY, { authMethod = HostAuthMethod.PRIVATE_KEY })
                Text("私钥")
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                RadioButton(authMethod == HostAuthMethod.KEY_OR_PASSWORD, { authMethod = HostAuthMethod.KEY_OR_PASSWORD })
                Text("私钥优先，密码兜底")
            }

            if (authMethod != HostAuthMethod.PRIVATE_KEY) {
                OutlinedTextField(
                    password, { password = it }, Modifier.fillMaxWidth(),
                    label = { Text(if (existing == null) "密码" else "密码（留空保持不变）") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
            }
            if (authMethod != HostAuthMethod.PASSWORD) {
                OutlinedTextField(
                    privateKey, { privateKey = it }, Modifier.fillMaxWidth(),
                    label = { Text(if (existing == null) "私钥 (PEM)" else "私钥 (PEM，留空保持不变)") },
                    minLines = 3,
                )
            }

            OutlinedTextField(tags, { tags = it }, Modifier.fillMaxWidth(), label = { Text("标签（逗号分隔）") }, singleLine = true)
            OutlinedTextField(
                quickCommands, { quickCommands = it }, Modifier.fillMaxWidth(),
                label = { Text("快速命令（每行「标签:命令」）") },
                minLines = 3,
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

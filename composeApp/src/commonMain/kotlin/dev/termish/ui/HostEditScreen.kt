package dev.termish.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.termish.data.ConnectionMode
import dev.termish.data.Host
import dev.termish.data.HostAuthMethod
import dev.termish.data.QuickCommand
import dev.termish.data.newId
import dev.termish.util.monospaceFontFamily

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
    var tags by remember { mutableStateOf(existing?.tags ?: emptyList()) }
    // 结构化快速命令：每行 label + command（可增删，不再用文本行解析）
    var quickCommands: List<EditableQuickCommand> by remember {
        mutableStateOf(
            existing?.quickCommands?.map { EditableQuickCommand(it.id, it.label, it.command) } ?: emptyList(),
        )
    }
    var startupCommand by remember { mutableStateOf(existing?.startupCommand ?: "") }
    var moshThemeSync by remember { mutableStateOf(existing?.moshThemeSync ?: false) }
    var moshUdpPort by remember { mutableStateOf((existing?.moshUdpPort ?: 0).toString()) }

    Scaffold(
        topBar = {
            TermishHeader(
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
                            // 系统由连接后自动探测写入（Termius 式），编辑页不手填；
                            // 保留已探测到的值，不覆盖。
                            system = existing?.system ?: "",
                            authMethod = authMethod,
                            connectionMode = connectionMode,
                            tags = tags,
                            quickCommands = quickCommands
                                .filter { it.label.isNotBlank() || it.command.isNotBlank() }
                                .map { QuickCommand(it.id, it.label.trim(), it.command.trim()) },
                            createdAt = existing?.createdAt ?: kotlinx.datetime.Clock.System.now().toEpochMilliseconds(),
                            lastConnectedAt = existing?.lastConnectedAt ?: 0L,
                            knownHostFingerprint = existing?.knownHostFingerprint,
                            startupCommand = startupCommand.trim(),
                            moshThemeSync = moshThemeSync,
                            moshUdpPort = moshUdpPort.toIntOrNull()?.takeIf { it in 1024..65535 } ?: 0,
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
            // Herdr：agent 工作台模式——SSH 底座 + herdr 探测 + agent 监控（选 Herdr = 显式同意监控）
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                RadioButton(connectionMode == ConnectionMode.HERDR, { connectionMode = ConnectionMode.HERDR })
                Column {
                    Text(s.editModeHerdr)
                    if (connectionMode == ConnectionMode.HERDR) {
                        Text(
                            s.editModeHerdrHint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
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

            // 主机标签：chip 输入框（回车/逗号变成可删除的 tag，避免自由文本碎片化）
            TagInputField(
                tags = tags,
                onTagsChange = { tags = it },
                label = s.editTags,
                modifier = Modifier.fillMaxWidth(),
            )
            // 快速命令：结构化行编辑（名称 + 命令，可增删）
            Text(s.editQuickCommands, style = MaterialTheme.typography.labelLarge)
            quickCommands.forEachIndexed { i, qc ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    OutlinedTextField(
                        qc.label, { qc.label = it },
                        Modifier.weight(1f),
                        label = { Text(s.quickCmdName) },
                        singleLine = true,
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        qc.command, { qc.command = it },
                        Modifier.weight(2f),
                        label = { Text(s.quickCmdCommand) },
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontFamily = monospaceFontFamily()),
                    )
                    IconButton(onClick = { quickCommands = quickCommands.filterIndexed { j, _ -> j != i } }) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = s.quickCmdDelete,
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        )
                    }
                }
            }
            TextButton(onClick = { quickCommands = quickCommands + EditableQuickCommand(newId(), "", "") }) {
                Icon(Icons.Filled.Add, contentDescription = s.quickCmdAdd, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(s.quickCmdAdd)
            }
            OutlinedTextField(
                startupCommand, { startupCommand = it }, Modifier.fillMaxWidth(),
                label = { Text(s.editStartupCommand) },
                placeholder = { Text(s.editStartupPlaceholder) },
                singleLine = true,
            )
            if (connectionMode == ConnectionMode.MOSH) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text(s.editMoshThemeSync, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(moshThemeSync, { moshThemeSync = it })
                }
                OutlinedTextField(
                    moshUdpPort, { moshUdpPort = it }, Modifier.fillMaxWidth(),
                    label = { Text(s.editMoshUdpPort) },
                    placeholder = { Text("0") },
                    supportingText = { Text(s.editMoshUdpPortHint) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        }
    }
}

/** 快速命令编辑行：可变包装（QuickCommand 为不可变 data class，UI 编辑用本类）。 */
private class EditableQuickCommand(val id: String, var label: String, var command: String)

/**
 * 标签 chip 输入框：输入后按回车/逗号 → 变成可删除的 tag 气泡（Gmail 收件人式）。
 * - 回车/逗号提交当前输入为一个 tag（支持一次粘贴多个逗号分隔的 tag）
 * - 输入框为空时按退格删除最后一个 tag
 * - IME 组合态（拼音等）不提交，只有 committed 文本才变 tag
 * - 自动去重（大小写不敏感）
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagInputField(
    tags: List<String>,
    onTagsChange: (List<String>) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    var state by remember { mutableStateOf(TextFieldValue("")) }
    val input = state.text
    val focusRequester = remember { FocusRequester() }

    fun commit() {
        // 支持一次输入/粘贴多个（逗号分隔）；去重（忽略大小写）
        val parts = input.split(',', '\n').map { it.trim().removePrefix("#") }.filter { it.isNotEmpty() }
        val merged = tags.toMutableList()
        for (p in parts) {
            if (merged.none { it.equals(p, ignoreCase = true) }) merged.add(p)
        }
        if (merged != tags) onTagsChange(merged)
        state = TextFieldValue("")
    }

    Box(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable { focusRequester.requestFocus() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            tags.forEach { t ->
                InputChip(
                    selected = true,
                    onClick = {},
                    label = { Text(t) },
                    trailingIcon = {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = null,
                            modifier = Modifier
                                .size(InputChipDefaults.IconSize)
                                .clickable { onTagsChange(tags - t) },
                        )
                    },
                )
            }
            BasicTextField(
                value = state,
                onValueChange = { new ->
                    // 组合态（拼音候选）：只更新视图，不提交
                    if (new.composition != null) {
                        state = new
                        return@BasicTextField
                    }
                    state = new
                    if (new.text.contains(',') || new.text.contains('\n')) {
                        commit()
                    }
                },
                modifier = Modifier
                    .width(120.dp)
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent { ev ->
                        if (ev.type == KeyEventType.KeyDown) {
                            when (ev.key) {
                                Key.Enter -> {
                                    commit()
                                    true
                                }
                                Key.Backspace -> {
                                    // 输入为空时退格 = 删除最后一个 tag
                                    if (state.text.isEmpty() && tags.isNotEmpty()) {
                                        onTagsChange(tags.dropLast(1))
                                        true
                                    } else {
                                        false
                                    }
                                }
                                else -> false
                            }
                        } else {
                            false
                        }
                    },
                textStyle = TextStyle(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { inner ->
                    Box {
                        // 空态占位提示：无标签且无输入时显示
                        if (tags.isEmpty() && input.isEmpty()) {
                            Text(
                                label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                            )
                        }
                        inner()
                    }
                },
            )
        }
    }
}



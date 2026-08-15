package dev.mssh.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.mssh.ssh.AuthPrompt
import dev.mssh.ui.theme.TerminalTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun TerminalScreen(
    controller: TerminalController,
    theme: TerminalTheme,
    settings: dev.mssh.data.AppSettings,
    onBack: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var ctrlActive by remember { mutableStateOf(false) }
    var altActive by remember { mutableStateOf(false) }
    var inputValue by remember { mutableStateOf(TextFieldValue("")) }
    val inputFocusRequester = remember { FocusRequester() }

    val appCursorKeys = controller.buffer.applicationCursorKeys

    LaunchedEffect(Unit) {
        controller.connect(80, 24)
    }

    // 光标闪烁：连接建立后周期性切换可见性并重绘
    LaunchedEffect(controller.status, settings.cursorBlink) {
        while (controller.status == ConnStatus.CONNECTED) {
            delay(530)
            if (settings.cursorBlink) {
                controller.blinkCursor()
            }
        }
    }

    val prompt = controller.authPrompt
    if (prompt != null) {
        AuthPromptDialog(prompt.prompt) { answers ->
            controller.respondToPrompt(answers)
        }
    }

    val hostKey = controller.hostKeyPrompt
    if (hostKey != null) {
        HostKeyDialog(hostKey.key) { accept ->
            controller.respondToHostKey(accept)
        }
    }

    Column(Modifier.fillMaxSize()) {
        // 状态栏
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(controller.title, style = MaterialTheme.typography.titleSmall)
            when (controller.status) {
                ConnStatus.CONNECTING, ConnStatus.AUTH -> CircularProgressIndicator(Modifier.padding(8.dp), strokeWidth = 2.dp)
                ConnStatus.ERROR -> Text("连接失败", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                ConnStatus.CLOSED -> Text("已断开", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                else -> {}
            }
            TextButton(onClick = { controller.close(); onBack() }) { Text("断开") }
        }

        controller.errorMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 8.dp))
        }

        // 快速命令
        if (settings.keyboardToolbarVisible && controller.isConnected()) {
            QuickCommandsBar(controller)
        }

        // 终端画布
        Box(Modifier.weight(1f)) {
            TerminalView(
                controller = controller,
                theme = theme,
                fontSizeSp = settings.terminalFontSize.toFloat(),
                onFocusKeyboard = { inputFocusRequester.requestFocus() },
                onCopy = { text ->
                    clipboard.setText(AnnotatedString(text))
                    scope.launch { snackbar.showSnackbar("已复制") }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        SnackbarHost(snackbar)

        // 输入框 + 键盘工具栏
        Column(Modifier.imePadding()) {
            if (settings.keyboardToolbarVisible) {
                KeyToolbar(
                    ctrlActive = ctrlActive,
                    altActive = altActive,
                    onToggleCtrl = { ctrlActive = !ctrlActive },
                    onToggleAlt = { altActive = !altActive },
                    applicationCursorKeys = appCursorKeys,
                    onKey = { key -> controller.sendBytes(specialKeyBytes(key, appCursorKeys)) },
                )
            }
            BasicTextField(
                value = inputValue,
                onValueChange = { new ->
                    val oldText = inputValue.text
                    val newText = new.text
                    when {
                        newText.length > oldText.length -> {
                            val added = newText.substring(oldText.length)
                            sendTyped(controller, added, ctrlActive, altActive)
                            ctrlActive = false
                            altActive = false
                        }
                        newText.length < oldText.length -> {
                            // 退格
                            repeat(oldText.length - newText.length) {
                                controller.sendBytes(byteArrayOf(0x7f))
                            }
                        }
                    }
                    // 输入换行（Enter）后清空输入行；否则保留以正确计算增量
                    inputValue = if (newText.contains('\n')) TextFieldValue("") else new
                },
                modifier = Modifier.fillMaxWidth().padding(4.dp).focusRequester(inputFocusRequester),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Ascii,
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                ),
                singleLine = false,
                decorationBox = { innerTextField ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
                            .padding(12.dp),
                    ) {
                        if (inputValue.text.isEmpty()) {
                            Text("输入命令…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        innerTextField()
                    }
                },
            )
        }
    }
}

private fun sendTyped(controller: TerminalController, text: String, ctrl: Boolean, alt: Boolean) {
    if (alt) controller.sendBytes(byteArrayOf(0x1b))
    for (ch in text) {
        if (ch == '\n') {
            // 终端 Enter 发送 CR（回车）
            controller.sendBytes(byteArrayOf(0x0d))
        } else if (ctrl && ch in 'a'..'z') {
            controller.sendBytes(byteArrayOf((ch.code and 0x1f).toByte()))
        } else {
            controller.sendBytes(ch.toString().encodeToByteArray())
        }
    }
}

@Composable
private fun QuickCommandsBar(controller: TerminalController) {
    val cmds = controller.quickCommands()
    if (cmds.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (cmd in cmds) {
            AssistChip(
                onClick = { controller.sendText(cmd.command + "\n") },
                label = { Text(cmd.label) },
            )
        }
    }
}

@Composable
private fun AuthPromptDialog(prompt: AuthPrompt, onResult: (List<String>?) -> Unit) {
    var values by remember { mutableStateOf(List(prompt.prompts.size) { "" }) }
    AlertDialog(
        onDismissRequest = { onResult(null) },
        title = { Text(if (prompt.name.isNotEmpty()) prompt.name else "需要认证") },
        text = {
            Column {
                if (prompt.instruction.isNotEmpty()) {
                    Text(prompt.instruction, style = MaterialTheme.typography.bodySmall)
                }
                prompt.prompts.forEachIndexed { i, field ->
                    OutlinedTextField(
                        value = values.getOrElse(i) { "" },
                        onValueChange = { v ->
                            val list = values.toMutableList()
                            list[i] = v
                            values = list
                        },
                        label = { Text(field.label) },
                        visualTransformation = if (field.echo) androidx.compose.ui.text.input.VisualTransformation.None
                        else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = { Button(onClick = { onResult(values) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = { onResult(null) }) { Text("取消") } },
    )
}

@Composable
private fun HostKeyDialog(key: dev.mssh.ssh.HostKeyInfo, onResult: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { onResult(false) },
        title = { Text("确认服务器身份") },
        text = {
            Column {
                Text("无法验证服务器主机的真实性。请核对指纹：")
                Text(
                    "${key.algorithm}\n${key.fingerprintSha256}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { Button(onClick = { onResult(true) }) { Text("信任并连接") } },
        dismissButton = { TextButton(onClick = { onResult(false) }) { Text("取消") } },
    )
}

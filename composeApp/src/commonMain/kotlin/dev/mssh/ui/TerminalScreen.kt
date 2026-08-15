package dev.mssh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.mssh.ssh.AuthPrompt
import dev.mssh.ui.theme.TerminalTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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
    // 已提交基线：输入法组合态（拼音等）不参与 diff，提交时才更新
    var committedText by remember { mutableStateOf("") }
    // 底部悬浮工具栏内容高度（不含导航条/键盘 padding），用于计算画布平移量
    var toolbarHeightPx by remember { mutableFloatStateOf(0f) }
    val inputFocusRequester = remember { FocusRequester() }
    var showDisconnectDialog by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    // 键盘是否弹出：ime inset 高于导航条即认为弹出（跨平台，isImeVisible 仅 Android 有）
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) >
        WindowInsets.navigationBars.getBottom(LocalDensity.current)
    // 点画布/键盘按钮：焦点变更与输入连接建立是异步的，立即 show() 常被忽略，
    // 延迟一小拍再显式拉起键盘。
    val showKeyboard: () -> Unit = {
        inputFocusRequester.requestFocus()
        scope.launch {
            delay(150)
            keyboardController?.show()
        }
    }

    // 返回：连接中先确认，否则直接关闭并回退
    val requestBack = {
        if (controller.isConnected()) {
            showDisconnectDialog = true
        } else {
            controller.close()
            onBack()
        }
    }

    // 拦截系统返回（手势/返回键），与点击返回按钮一致
    PlatformBackHandler(enabled = true, onBack = requestBack)

    val appCursorKeys = controller.buffer.applicationCursorKeys

    // OSC 52：远端程序（nvim/yazi/tmux）写系统剪贴板
    controller.onRemoteClipboard = { text ->
        clipboard.setText(AnnotatedString(text))
        scope.launch { snackbar.showSnackbar("远端已写入剪贴板") }
    }

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

    if (showDisconnectDialog) {
        AlertDialog(
            onDismissRequest = { showDisconnectDialog = false },
            title = { Text("断开连接") },
            text = { Text("当前会话已连接，确定要断开并返回吗？") },
            confirmButton = {
                Button(onClick = {
                    showDisconnectDialog = false
                    controller.close()
                    onBack()
                }) { Text("断开并返回") }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectDialog = false }) { Text("取消") }
            },
        )
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // 头部：返回按钮 + 标题 + 状态，底部分隔线与终端画布区分
        Column(Modifier.fillMaxWidth().background(theme.background())) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { requestBack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = theme.foreground())
                }
                Text(controller.title, style = MaterialTheme.typography.titleSmall, color = theme.foreground())
                Spacer(Modifier.weight(1f))
                when (controller.status) {
                    ConnStatus.CONNECTING, ConnStatus.AUTH -> CircularProgressIndicator(Modifier.padding(8.dp), strokeWidth = 2.dp)
                    ConnStatus.ERROR -> Text("连接失败", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                    ConnStatus.CLOSED -> Text("已断开", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                    else -> {}
                }
            }
            HorizontalDivider(color = theme.foreground().copy(alpha = 0.15f))
        }

        controller.errorMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 8.dp))
        }

        // 快速命令
        if (settings.keyboardToolbarVisible && controller.isConnected()) {
            QuickCommandsBar(controller)
        }

        // 终端画布 + 悬浮工具栏：键盘弹起时不挤压画布（adjustNothing），避免 vim/tmux
        // 等全屏程序随键盘弹收反复 SIGWINCH 重排；由 TerminalView 向上平移保证光标可见。
        // 末尾的 8dp 是光标与工具栏之间的视觉间距余量。
        val density = LocalDensity.current
        val coveredBottomPx = maxOf(
            WindowInsets.ime.getBottom(density).toFloat(),
            WindowInsets.navigationBars.getBottom(density).toFloat(),
        ) + toolbarHeightPx + with(density) { 12.dp.toPx() }
        Box(Modifier.weight(1f).clipToBounds()) {
            TerminalView(
                controller = controller,
                theme = theme,
                fontSizeSp = settings.terminalFontSize.toFloat(),
                targetCols = settings.terminalTargetCols,
                coveredBottomPx = coveredBottomPx,
                onFocusKeyboard = { showKeyboard() },
                onCopy = { text ->
                    clipboard.setText(AnnotatedString(text))
                    scope.launch { snackbar.showSnackbar("已复制") }
                },
                modifier = Modifier.fillMaxSize(),
            )

            SnackbarHost(snackbar, Modifier.align(Alignment.TopCenter))

            // 底部悬浮键盘工具栏：不透明背景 + 顶部分隔线，与系统键盘/终端内容拉开层次。
            // 外层负责避让导航条/键盘；内层单独测内容高度（padding 会算进
            // 外层总高，直接测外层会把键盘高度重复计入平移量）。
            Column(
                Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(theme.background())
                    .navigationBarsPadding()
                    .imePadding(),
            ) {
                Column(Modifier.onSizeChanged { toolbarHeightPx = it.height.toFloat() }) {
                    HorizontalDivider(color = theme.foreground().copy(alpha = 0.2f))
                    Spacer(Modifier.height(6.dp))
                if (settings.keyboardToolbarVisible) {
                    KeyToolbar(
                        ctrlActive = ctrlActive,
                        altActive = altActive,
                        onToggleCtrl = { ctrlActive = !ctrlActive },
                        onToggleAlt = { altActive = !altActive },
                        applicationCursorKeys = appCursorKeys,
                        onKey = { key ->
                            controller.sendBytes(specialKeyBytes(key, appCursorKeys))
                            // 任何按键发出后消耗粘性修饰键，避免 CTRL 残留污染后续输入
                            // （残留会导致下个字母变成 Ctrl+D/C 等而意外退出会话）
                            ctrlActive = false
                            altActive = false
                        },
                        onToggleKeyboard = {
                            if (imeVisible) keyboardController?.hide() else showKeyboard()
                        },
                        onPaste = {
                            val text = clipboard.getText()?.text
                            if (!text.isNullOrEmpty()) {
                                // bracketed paste：远端开启 2004 时包裹转义序列，
                                // vim/nvim 等不会把粘贴内容当手打（避免自动缩进错乱）
                                val payload = if (controller.buffer.bracketedPaste) {
                                    "\u001b[200~$text\u001b[201~"
                                } else text
                                controller.sendText(payload)
                                scope.launch { snackbar.showSnackbar("已粘贴") }
                            }
                            ctrlActive = false
                            altActive = false
                        },
                        theme = theme,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                // 隐藏输入字段：仅作为输入法接入口，不渲染可见 UI。
                // 打字内容以终端回显为准（远端 echo 才是真实状态，本地显示反而重复干扰）。
                BasicTextField(
                    value = inputValue,
                    onValueChange = { new ->
                        if (new.composition != null) {
                            // 输入法组合中（拼音等）：只更新视图，不发送
                            inputValue = new
                            return@BasicTextField
                        }
                        val oldText = committedText
                        val newText = new.text
                        if (newText != oldText) {
                            // 公共前缀 diff：纯尾部增删是精确操作；
                            // 中间编辑无法映射到远端光标，按删尾重发处理
                            var p = 0
                            val maxP = minOf(oldText.length, newText.length)
                            while (p < maxP && oldText[p] == newText[p]) p++
                            repeat(oldText.length - p) { controller.sendBytes(byteArrayOf(0x7f)) }
                            val added = newText.substring(p)
                            if (added.isNotEmpty()) {
                                sendTyped(controller, added, ctrlActive, altActive)
                                ctrlActive = false
                                altActive = false
                            }
                        }
                        committedText = newText
                        // Enter 后或缓冲过长时清空输入框
                        if (newText.contains('\n') || newText.length > 64) {
                            committedText = ""
                            inputValue = TextFieldValue("")
                        } else {
                            inputValue = new
                        }
                    },
                    modifier = Modifier.size(1.dp).alpha(0f)
                        .focusRequester(inputFocusRequester)
                        // 退格：输入框有内容时不拦截（平台删除 → onValueChange diff 发送，
                        // 光标/选区由系统维护，不会错乱）；仅输入框为空时（Enter 后/快速命令后）
                        // 拦截并直接发 0x7f，否则远端行有内容却删不掉。
                        // 输入法组合态除外：组合文本尚未发给远端，让 IME 自己删。
                        .onPreviewKeyEvent { ev ->
                            if (ev.type == KeyEventType.KeyDown && ev.key == Key.Backspace &&
                                inputValue.composition == null && committedText.isEmpty()
                            ) {
                                controller.sendBytes(byteArrayOf(0x7f))
                                true
                            } else false
                        }
                        // 兜底：焦点真正到手后再 show()，解决部分机型 requestFocus
                        // 后立即 show 被忽略（键盘拉不起来）的问题
                        .onFocusChanged { if (it.isFocused) keyboardController?.show() },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = theme.foreground()),
                    cursorBrush = SolidColor(Color.Transparent),
                    keyboardOptions = KeyboardOptions(
                        // 用 Text 而不是 Ascii：Ascii 会让输入法禁用中文候选
                        keyboardType = KeyboardType.Text,
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                    ),
                    // 多行模式以捕获输入法 Enter（\n → CR）
                    singleLine = false,
                )
                }
            }
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

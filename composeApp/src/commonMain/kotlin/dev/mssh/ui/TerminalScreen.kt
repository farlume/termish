package dev.mssh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mssh.ssh.AuthPrompt
import dev.mssh.term.argbToRgb
import dev.mssh.ui.theme.TerminalTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun TerminalScreen(
    controller: TerminalController,
    sessions: List<TerminalController>,
    theme: TerminalTheme,
    settings: dev.mssh.data.AppSettings,
    onBack: () -> Unit,
    onSwitchTab: (TerminalController) -> Unit,
    onAddSession: () -> Unit,
    onCloseTab: (TerminalController) -> Unit,
    onSftp: () -> Unit,
) {
    val s = LocalAppStrings.current
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

    // OSC 10/11/12 应答用的默认色跟随当前终端主题：
    // herdr 等 TUI 会查询默认前景/背景色来决定自身配色，
    // 若硬编码暗色值，浅色主题下会把整个界面渲染成黑色。
    LaunchedEffect(theme) {
        val b = controller.buffer
        b.defaultFgRgb = argbToRgb(theme.foreground)
        b.defaultBgRgb = argbToRgb(theme.background)
        b.defaultCursorRgb = argbToRgb(theme.cursor)
    }

    // 返回即退到列表：会话默认在后台保持运行（SessionManager/前台服务保活），
    // 不弹保留策略选择。拦截系统返回（手势/返回键）与点击返回按钮一致。
    PlatformBackHandler(enabled = true, onBack = onBack)

    val appCursorKeys = controller.buffer.applicationCursorKeys

    // OSC 52：远端程序（nvim/yazi/tmux）写系统剪贴板（静默写入，不弹提示，
    // 避免 herdr 拖拽/滚动误触复制时反复打扰）
    controller.onRemoteClipboard = { text ->
        clipboard.setText(AnnotatedString(text))
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

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // 头部：返回 + 多会话 tab（系统 logo + user@host + X）+ 添加菜单
        TerminalTabBar(
            sessions = sessions,
            current = controller,
            onBack = onBack,
            onSwitch = onSwitchTab,
            onAdd = onAddSession,
            onClose = onCloseTab,
            onSftp = onSftp,
            theme = theme,
        )

        // 会话主体：切换 tab 时按会话唯一 id 整体重组（输入框/局部状态独立）
        key(controller.sessionId) {
            // 等 TerminalView 量到真实画布尺寸后再建连，避免 PTY 先以 80x24 起、
            // 让 herdr 等远端复用器附着瞬间按错误窗口尺寸布局。
            // 放 key 块内：每个会话独立，切换/新建 tab 都会重新建连。
            var connectSent by remember { mutableStateOf(false) }

            // 重连/错误飘条：重连中=琥珀色，失败=红色；跟随终端主题背景
        val bannerText = when {
            controller.status == ConnStatus.CONNECTING && controller.reconnectCount > 0 ->
                s.terminalReconnectingN(controller.reconnectCount)
            // mosh 链路失联（会话保持中，网络恢复自动续传）：对齐 mosh 的
            // "Last contact Ns ago" 提示；状态点同步变琥珀色「失联中」
            controller.linkLostSeconds >= LINK_LOST_THRESHOLD_SECONDS ->
                s.terminalMoshLostContact(controller.linkLostSeconds)
            controller.errorMessage != null -> controller.errorMessage
            controller.status == ConnStatus.ERROR -> s.terminalFailed
            else -> null
        }
        bannerText?.let { message ->
            val bannerColor = if (controller.status == ConnStatus.ERROR || controller.status == ConnStatus.CLOSED) {
                androidx.compose.ui.graphics.Color(0xFFEF5350)
            } else {
                androidx.compose.ui.graphics.Color(0xFFFFA726)
            }
            Text(
                message,
                color = bannerColor,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(bannerColor.copy(alpha = 0.12f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        // 快速命令
        if (settings.keyboardToolbarVisible && controller.isConnected()) {
            QuickCommandsBar(controller)
        }

        // 终端画布 + 悬浮工具栏：画布底边与工具栏顶边对齐（不再被工具栏盖住）。
        // 键盘弹起时不挤压画布（adjustNothing），工具栏上移会遮住画布底部，
        // 由 TerminalView 向上平移保证光标可见；键盘收起即归位，顶部（herdr
        // 菜单等）始终可见。末尾 12dp 是画布与工具栏之间的视觉间距余量。
        val density = LocalDensity.current
        val coveredBottomPx =
            WindowInsets.ime.getBottom(density).toFloat() + with(density) { 12.dp.toPx() }
        // 画布四周留出小间距，文字不贴屏幕边缘；行列按内缩后宽度自动重算
        Box(Modifier.weight(1f).clipToBounds().background(theme.background())) {
            TerminalView(
                controller = controller,
                theme = theme,
                fontSizeSp = settings.terminalFontSize.toFloat(),
                targetCols = settings.terminalTargetCols,
                coveredBottomPx = coveredBottomPx,
                keyboardVisible = imeVisible,
                onReady = { cols, rows ->
                    // 等工具栏高度量到后再建连：画布尺寸已扣除工具栏区域，
                    // 避免先用"含被盖住区域"的错误行数起 PTY
                    if (!connectSent && toolbarHeightPx > 0f) {
                        connectSent = true
                        controller.connect(cols, rows)
                    }
                },
                onCopy = { text ->
                    clipboard.setText(AnnotatedString(text))
                    scope.launch { snackbar.showSnackbar(s.terminalCopied) }
                },
                modifier = Modifier.fillMaxSize()
                    .padding(horizontal = 6.dp, vertical = 4.dp)
                    .padding(bottom = with(density) { toolbarHeightPx.toDp() + 12.dp }),
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
                                scope.launch { snackbar.showSnackbar(s.terminalPasted) }
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
                        .onFocusChanged {
                            // DECSET 1004：聚焦/失焦事件（vim/tmux 据此刷新界面状态）
                            if (controller.buffer.focusEvents) {
                                controller.sendBytes(
                                    if (it.isFocused) "\u001b[I".encodeToByteArray()
                                    else "\u001b[O".encodeToByteArray()
                                )
                            }
                            if (it.isFocused) keyboardController?.show()
                        },
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
}

/**
 * 终端页多会话 tab 栏：返回 + 可滑动的会话 tab（系统 logo + user@host + X）
 * + 添加按钮（Connect / Connect via SFTP，均有图标）。
 */
@Composable
private fun TerminalTabBar(
    sessions: List<TerminalController>,
    current: TerminalController,
    onBack: () -> Unit,
    onSwitch: (TerminalController) -> Unit,
    onAdd: () -> Unit,
    onClose: (TerminalController) -> Unit,
    onSftp: () -> Unit,
    theme: TerminalTheme,
) {
    val s = LocalAppStrings.current
    var addOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(theme.background())) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = s.navBack,
                    tint = theme.foreground(),
                )
            }
            // 会话 tab：水平滑动
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                sessions.forEach { c ->
                    SessionTabChip(
                        controller = c,
                        selected = c === current,
                        theme = theme,
                        onClick = { onSwitch(c) },
                        onClose = { onClose(c) },
                    )
                }
            }
            Box {
                IconButton(onClick = { addOpen = true }) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = s.hostsAdd,
                        tint = theme.foreground(),
                    )
                }
                DropdownMenu(expanded = addOpen, onDismissRequest = { addOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(s.hostsConnect) },
                        leadingIcon = { Icon(Icons.Filled.Link, null) },
                        onClick = {
                            addOpen = false
                            onAdd()
                        },
                    )
                    // SFTP 尚未实现：菜单项展示但置灰不可点
                    DropdownMenuItem(
                        text = { Text(s.hostsConnectSftp) },
                        leadingIcon = { Icon(Icons.Filled.FolderOpen, null) },
                        enabled = false,
                        onClick = {
                            addOpen = false
                            onSftp()
                        },
                    )
                }
            }
        }
    }
}

/** 单个会话 tab：系统小头像 + user@host + 关闭 X；当前 tab 高亮并显示连接状态点。 */
@Composable
private fun SessionTabChip(
    controller: TerminalController,
    selected: Boolean,
    theme: TerminalTheme,
    onClick: () -> Unit,
    onClose: () -> Unit,
) {
    val sys = controller.host.system.ifBlank { controller.host.hostname }
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) theme.foreground().copy(alpha = 0.18f)
                else theme.foreground().copy(alpha = 0.07f),
            )
            .clickable(onClick = onClick)
            .padding(start = 8.dp, top = 5.dp, bottom = 5.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 系统小头像
        Box(
            Modifier.size(18.dp).clip(RoundedCornerShape(5.dp)).background(systemColor(sys)),
            contentAlignment = Alignment.Center,
        ) {
            val svg = systemSvg(sys)
            if (svg != null) {
                Icon(painterResource(svg), null, tint = Color.White, modifier = Modifier.size(11.dp))
            } else {
                Icon(systemIcon(sys), null, tint = Color.White, modifier = Modifier.size(11.dp))
            }
        }
        Spacer(Modifier.size(6.dp))
        Text(
            "${controller.host.username}@${controller.host.hostname}",
            style = MaterialTheme.typography.labelSmall,
            color = theme.foreground(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.size(4.dp))
        if (selected) {
            Box(
                Modifier.size(8.dp).clip(CircleShape)
                    .background(statusColor(controller.status, controller.linkLostSeconds)),
            )
        }
        IconButton(onClick = onClose, modifier = Modifier.size(22.dp)) {
            Icon(
                Icons.Filled.Close,
                contentDescription = null,
                tint = theme.foreground().copy(alpha = 0.7f),
                modifier = Modifier.size(14.dp),
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

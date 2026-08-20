package dev.termish.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.termish.data.AppSettings
import dev.termish.data.Host
import dev.termish.data.HostRepository
import dev.termish.ssh.SftpSession
import dev.termish.ssh.AuthPrompt
import dev.termish.term.argbToRgb
import dev.termish.ui.theme.StatusColors
import dev.termish.ui.theme.TerminalTheme
import dev.termish.util.monospaceFontFamily
import dev.termish.util.hapticTick
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 终端页会话 tab：SSH/Mosh 终端 或 SFTP 文件浏览器。 */
sealed interface SessionTab {
    val id: String

    data class Terminal(val controller: TerminalController) : SessionTab {
        override val id: String get() = controller.sessionId
    }

    data class Sftp(
        val host: Host,
        val session: SftpSession?,
        val uiState: SftpUiState = SftpUiState(),
    ) : SessionTab {
        override val id: String get() = "sftp:${host.id}:${session?.hashCode() ?: "restored"}"
    }
}

/** 终端页当前 tab 的配色：终端 tab 用终端主题，应用类页面 tab 用应用主题。 */
@Composable
private fun tabColors(current: SessionTab, theme: TerminalTheme): Pair<Color, Color> =
    if (current is SessionTab.Terminal) {
        theme.background() to theme.foreground()
    } else {
        MaterialTheme.colorScheme.background to MaterialTheme.colorScheme.onSurface
    }

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun TerminalScreen(
    tabs: List<SessionTab>,
    current: SessionTab,
    theme: TerminalTheme,
    settings: AppSettings,
    /** 终端内插入命令片段（全局库读取）。 */
    repository: HostRepository,
    onBack: () -> Unit,
    onSwitchTab: (SessionTab) -> Unit,
    onAddSession: () -> Unit,
    onCloseTab: (SessionTab) -> Unit,
    onOpenSftpPicker: () -> Unit,
    /** SFTP 断线重连：重建会话后替换 tab 中的 session（由 AppRoot 实现）。 */
    onReconnectSftp: (SessionTab.Sftp) -> Unit = {},
) {
    val s = LocalAppStrings.current
    // 关闭 tab 前确认：x 太容易误触
    var pendingClose by remember { mutableStateOf<SessionTab?>(null) }
    // 背景在 statusBarsPadding 之前应用：状态栏/灵动岛区域与页面同色，
    // 避免终端黑色时顶部露出 App 浅色背景（或终端白色时露出深色背景）
    val (pageBackground, pageForeground) = tabColors(current, theme)
    Column(Modifier.fillMaxSize().background(pageBackground).statusBarsPadding()) {
        TerminalTabBar(
            tabs = tabs,
            current = current,
            barBackground = pageBackground,
            barForeground = pageForeground,
            onBack = onBack,
            onSwitch = onSwitchTab,
            onAdd = onAddSession,
            onClose = { pendingClose = it },
            onSftp = onOpenSftpPicker,
            theme = theme,
        )
        when (val tab = current) {
            is SessionTab.Terminal -> TerminalBody(
                controller = tab.controller,
                theme = theme,
                settings = settings,
                repository = repository,
                onBack = onBack,
            )
            is SessionTab.Sftp -> SftpContent(
                host = tab.host,
                session = tab.session,
                state = tab.uiState,
                onBack = onBack,
                onReconnect = { onReconnectSftp(tab) },
            )
        }
    }

    pendingClose?.let { tab ->
        val host = when (tab) {
            is SessionTab.Terminal -> tab.controller.host
            is SessionTab.Sftp -> tab.host
        }
        AlertDialog(
            onDismissRequest = { pendingClose = null },
            title = { Text(s.terminalCloseTabTitle) },
            text = { Text(s.terminalCloseTabBody("${host.username}@${host.hostname}")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingClose = null
                        onCloseTab(tab)
                    },
                ) {
                    Text(s.terminalConfirm)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingClose = null }) {
                    Text(s.terminalCancel)
                }
            },
        )
    }
}

/** 终端主体：banner + 画布 + 工具栏 + 输入框（切换 tab 按会话 id 重组）。 */
@Composable
private fun TerminalBody(
    controller: TerminalController,
    theme: TerminalTheme,
    settings: AppSettings,
    repository: HostRepository,
    onBack: () -> Unit,
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
    // 命令片段插入面板
    var snippetOpen by remember { mutableStateOf(false) }
    // Git 面板（FAB 与工具栏「⎇」键共用开关）
    var gitPanelOpen by remember { mutableStateOf(false) }
    // 面板打开时拦截系统返回：先关面板，不直接退回首页
    PlatformBackHandler(enabled = snippetOpen) { snippetOpen = false }
    // 底部悬浮工具栏内容高度（不含导航条/键盘 padding），用于计算画布平移量
    // （键盘弹起时展开行的覆盖增量）
    var toolbarHeightPx by remember { mutableFloatStateOf(0f) }
    // 工具栏常驻两行高度（不含展开行 3/4）：画布布局只按它算——展开/收起
    // 不改画布尺寸，也就不触发 PTY resize（TUI 整体重排闪屏）；展开行覆盖画布
    var toolbarBaseHeightPx by remember { mutableFloatStateOf(0f) }
    // 工具栏整体遮盖高度（含导航条/键盘 padding）：连接指示器据此在
    // 「画布−工具栏」可见区域内居中，视觉重心不上偏（同 HerdrInstallGuide）
    var toolbarOverlayPx by remember { mutableFloatStateOf(0f) }
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

    // 连接成功触感反馈（刚进入 CONNECTED 的那一次）
    var prevStatus by remember { mutableStateOf(controller.status) }
    LaunchedEffect(controller.status) {
        if (controller.status == ConnStatus.CONNECTED && prevStatus != ConnStatus.CONNECTED) {
            if (settings.hapticFeedback) hapticTick()
        }
        prevStatus = controller.status
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // 会话主体：切换 tab 时按会话唯一 id 整体重组（输入框/局部状态独立）
        key(controller.sessionId) {
            // 等 TerminalView 量到真实画布尺寸后再建连，避免 PTY 先以 80x24 起、
            // 让 herdr 等远端复用器附着瞬间按错误窗口尺寸布局。
            // 放 key 块内：每个会话独立，切换/新建 tab 都会重新建连。
            var connectSent by remember { mutableStateOf(false) }
            // 工具栏展开行 3/4（会话内保持）：状态上提至调用方，画布按常驻高度
            // 布局、展开行覆盖画布（不 resize 不闪）；记忆在 key 块内随会话独立
            var toolbarExpanded by remember { mutableStateOf(false) }

        // 顶部 banner 文案：错误/断开/失联（连接中走画布居中指示器，见下）
        val bannerText = when {
            controller.linkLostSeconds >= LINK_LOST_THRESHOLD_SECONDS ->
                s.terminalMoshLostContact(controller.linkLostSeconds)
            controller.errorMessage != null -> controller.errorMessage
            controller.status == ConnStatus.ERROR -> s.terminalFailed
            else -> null
        }
        // 状态标志：浮层 banner 用（关闭/重连按钮的显隐）
        val bannerIsError = controller.status == ConnStatus.ERROR || controller.status == ConnStatus.CLOSED
        // ✕ 仅当 banner 实际显示 errorMessage 文案时才出现：连接中/失联文案
        // 优先级更高，此时清 errorMessage 不改变文案、徒增困惑
        val bannerDismissable = controller.errorMessage != null &&
            controller.status != ConnStatus.CONNECTING &&
            controller.linkLostSeconds < LINK_LOST_THRESHOLD_SECONDS
        val bannerReconnectable = controller.status == ConnStatus.ERROR || controller.status == ConnStatus.CLOSED

        // 终端画布 + 悬浮工具栏：画布底边与工具栏顶边对齐（不被工具栏盖住）。
        // 画布按「常驻两行」高度布局（[toolbarBaseHeightPx]）：展开行 3/4 覆盖
        // 画布底部而非压缩画布——不触发 PTY resize，herdr 等 TUI 不重排不闪屏。
        // 键盘弹起时不挤压画布（adjustNothing），工具栏上移会遮住画布底部，
        // 由 TerminalView 向上平移保证光标可见；平移量除键盘外也计入展开行
        // 覆盖高度（键盘收起即归位，顶部 herdr 菜单等始终可见）。
        // 末尾 12dp 是画布与工具栏之间的视觉间距余量。
        val density = LocalDensity.current
        // 导航条高度（px，键盘收起时的值）：工具栏被 navigationBarsPadding 抬高，
        // 而 TerminalView 的让位基于 Box 底（延伸到屏底）——不补 nav 会重叠
        // nav-9dp（手势导航约 25dp → 终端底行被盖住大半）。remember 固定首次
        // 值：键盘弹起时 navigationBars inset 可能变化（报 0），固定值防止画布
        // 随键盘 resize（TUI 重排闪屏）
        val navInsets = WindowInsets.navigationBars
        val navBarsBottomPx = remember { navInsets.getBottom(density) }
        val coveredBottomPx = WindowInsets.ime.getBottom(density).toFloat() +
            with(density) { 12.dp.toPx() } +
            // 展开行 3/4 的覆盖高度（展开时 >0；仅键盘弹起时平移生效）
            (toolbarHeightPx - toolbarBaseHeightPx)
        // 画布四周留出小间距，文字不贴屏幕边缘；行列按内缩后宽度自动重算
        Box(Modifier.weight(1f).clipToBounds().background(theme.background())) {
            when {
                controller.herdrNeedsInstall || controller.herdrInstalling -> {
                    // 底部让出工具栏高度：卡片在「画布−工具栏」区域内居中，视觉重心不上偏
                    HerdrInstallGuide(controller, with(density) { (toolbarHeightPx + navBarsBottomPx).toDp() })
                }
                controller.moshNeedsInstall || controller.moshInstalling -> {
                    MoshInstallGuide(controller, with(density) { (toolbarHeightPx + navBarsBottomPx).toDp() })
                }
                else -> {
                    TerminalView(
                        controller = controller,
                        theme = theme,
                        fontSizeSp = settings.terminalFontSize.toFloat(),
                        targetCols = settings.terminalTargetCols,
                        coveredBottomPx = coveredBottomPx,
                        keyboardVisible = imeVisible,
                        onReady = { cols, rows ->
                            // 等工具栏高度量到后（sizeStable）建连：画布尺寸已扣除工具栏区域，
                            // 避免先用"含被盖住区域"的错误行数起 PTY
                            if (!connectSent && toolbarBaseHeightPx > 0f) {
                                connectSent = true
                                controller.connect(cols, rows)
                            }
                        },
                        // 首帧工具栏未量到时画布偏高是中间尺寸：跳过 resize/onReady，
                        // 避免错误行数起 PTY 后再 resize（TUI 整体重排跳动）
                        sizeStable = toolbarBaseHeightPx > 0f,
                        onCopy = { text ->
                            clipboard.setText(AnnotatedString(text))
                            scope.launch { snackbar.showSnackbar(s.terminalCopied) }
                        },
                        modifier = Modifier.fillMaxSize()
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                            // 只让出常驻两行高度：展开行 3/4 覆盖画布（不压缩、
                            // 不 resize、不重排），展开收起零闪屏。
                            // 让位补导航条：工具栏被 navigationBarsPadding 抬高，
                            // 不补则终端底行被工具栏盖住（见 navBarsBottomPx）
                            .padding(bottom = with(density) { (toolbarBaseHeightPx + navBarsBottomPx).toDp() + 12.dp }),
                    )
                }
            }

            // 连接中：可见画布内居中指示器（首连/重连共用），淡入淡出——连接完成
            // 平滑消失，不留顶部浮层痕迹（错误/断开/失联仍走顶部 banner）。
            // 颜色随终端主题：应用浅色主题时不在深色画布上浮出亮色胶囊
            ConnectingIndicator(
                visible = controller.status == ConnStatus.CONNECTING,
                text = if (controller.reconnectCount > 0) {
                    s.terminalReconnectingN(controller.reconnectCount)
                } else {
                    s.terminalConnecting
                },
                bottomOverlay = with(density) { toolbarOverlayPx.toDp() },
                containerColor = theme.background(),
                contentColor = theme.foreground(),
                modifier = Modifier.align(Alignment.Center),
            )

            // 顶部浮层 banner：盖在画布上（不占布局空间、不把画布顶下来）
            bannerText?.let { message ->
                ErrorBanner(
                    message = message,
                    isError = bannerIsError,
                    onDismiss = if (bannerDismissable) { { controller.dismissError() } } else null,
                    onReconnect = if (bannerReconnectable) { { controller.reconnect() } } else null,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }

            SnackbarHost(snackbar, Modifier.align(Alignment.TopCenter))

            // Git 悬浮面板：右侧悬浮按钮（可拖动）+ 状态/diff 面板（跟随终端主题）。
            // git 命令走独立 exec 通道（SSH 复用已认证连接 / mosh 控制面连接），
            // 不注入交互终端；工具栏「⎇」键可展开面板。
            GitOverlay(
                controller = controller,
                theme = theme,
                open = gitPanelOpen,
                onOpenChange = { gitPanelOpen = it },
                bottomInset = with(density) { (toolbarHeightPx + navBarsBottomPx).toDp() + 12.dp },
                onToast = { msg -> scope.launch { snackbar.showSnackbar(msg) } },
                modifier = Modifier.align(Alignment.Center).fillMaxSize(),
            )

            // 底部悬浮键盘工具栏：不透明背景 + 顶部分隔线，与系统键盘/终端内容拉开层次。
            // 外层负责避让导航条/键盘；内层单独测内容高度（padding 会算进
            // 外层总高，直接测外层会把键盘高度重复计入平移量）。
            Column(
                Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(theme.background())
                    .navigationBarsPadding()
                    .imePadding()
                    // 测整体遮盖高度（含 padding；键盘弹起时工具栏随 imePadding
                    // 上移，遮盖高度随之增大，指示器居中区自动收缩到键盘之上）
                    .onSizeChanged { toolbarOverlayPx = it.height.toFloat() },
            ) {
                Column(Modifier.onSizeChanged { toolbarHeightPx = it.height.toFloat() }) {
                    HorizontalDivider(color = theme.foreground().copy(alpha = 0.2f))
                    Spacer(Modifier.height(6.dp))
                // 键盘工具栏常驻：手机系统键盘无 CTRL/ESC/方向键，关掉终端不可用
                KeyToolbar(
                        ctrlActive = ctrlActive,
                        altActive = altActive,
                        onToggleCtrl = { ctrlActive = !ctrlActive },
                        onToggleAlt = { altActive = !altActive },
                        applicationCursorKeys = appCursorKeys,
                        expanded = toolbarExpanded,
                        onExpandedChange = { toolbarExpanded = it },
                        // 回报高度必须含 KeyToolbar 全部自身 padding（含底部 10dp）
                        // ——画布按它让位，缺了哪层 padding 就会「害羞」地盖住终端底行
                        onBaseHeightChanged = { toolbarBaseHeightPx = it.toFloat() },
                        onKey = { key ->
                            if (settings.hapticFeedback) hapticTick()
                            controller.sendBytes(specialKeyBytes(key, appCursorKeys))
                            // 任何按键发出后消耗粘性修饰键，避免 CTRL 残留污染后续输入
                            // （残留会导致下个字母变成 Ctrl+D/C 等而意外退出会话）
                            ctrlActive = false
                            altActive = false
                        },
                        onToggleKeyboard = {
                            if (settings.hapticFeedback) hapticTick()
                            if (imeVisible) keyboardController?.hide() else showKeyboard()
                        },
                        onSnippets = {
                            if (settings.hapticFeedback) hapticTick()
                            snippetOpen = true
                        },
                        onGit = {
                            if (settings.hapticFeedback) hapticTick()
                            gitPanelOpen = true
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
                        onChar = { text -> controller.sendBytes(text.encodeToByteArray()) },
                        theme = theme,
                    )
                // 命令片段插入面板（键盘工具栏「{}」触发）
                if (snippetOpen) {
                    SnippetInsertSheet(
                        repository = repository,
                        theme = theme,
                        onUse = { content, run ->
                            controller.sendText(if (run) content + "\n" else content)
                            snippetOpen = false
                            scope.launch { snackbar.showSnackbar(s.snippetInserted) }
                        },
                        onDismiss = { snippetOpen = false },
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
    tabs: List<SessionTab>,
    current: SessionTab,
    barBackground: Color,
    barForeground: Color,
    onBack: () -> Unit,
    onSwitch: (SessionTab) -> Unit,
    onAdd: () -> Unit,
    onClose: (SessionTab) -> Unit,
    onSftp: () -> Unit,
    theme: TerminalTheme,
) {
    val s = LocalAppStrings.current
    var addOpen by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    // 滚动容器与各 tab 的布局位置：点击 tab 时滚动到居中
    val containerX = remember { mutableStateOf(0f) }
    val containerWidth = remember { mutableStateOf(0f) }
    val chipLayout = remember { mutableStateMapOf<String, Pair<Float, Float>>() }

    fun scrollToCenter(sessionId: String) {
        val (x, w) = chipLayout[sessionId] ?: return
        // chip 在内容中的偏移 = 渲染位置 - 容器位置 + 当前滚动量
        val offsetInContent = x - containerX.value + scroll.value
        val target = offsetInContent - (containerWidth.value - w) / 2
        scope.launch {
            scroll.animateScrollTo(target.toInt().coerceIn(0, scroll.maxValue))
        }
    }

    // 当前 tab 变化（从首页进入 / 切换 / 新会话选中）时自动滚到正中：
    // chip 布局与滚动范围都是异步测量（onGloballyPositioned / horizontalScroll），
    // 首帧可能还没量到，轮询等几帧全部就绪后再滚（等不到就放弃，不阻塞）
    LaunchedEffect(current.id) {
        var attempts = 0
        while (attempts < 20 &&
            (chipLayout[current.id] == null || containerWidth.value <= 0f || scroll.maxValue <= 0)
        ) {
            delay(16)
            attempts++
        }
        scrollToCenter(current.id)
    }

    Column(Modifier.fillMaxWidth().background(barBackground)) {
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
                    tint = barForeground,
                )
            }
            // 会话 tab：水平滑动。可视宽度由外层 Box 测量（horizontalScroll 的
            // Row 会按内容无限宽测量，onSizeChanged 拿到的是内容总宽而非视口宽，
            // 居中公式会被撑爆）
            Box(
                Modifier
                    .weight(1f)
                    .onSizeChanged { containerWidth.value = it.width.toFloat() },
            ) {
                Row(
                    Modifier
                        .horizontalScroll(scroll)
                        .onGloballyPositioned { containerX.value = it.positionInRoot().x },
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                tabs.forEach { tab ->
                    val host = when (tab) {
                        is SessionTab.Terminal -> tab.controller.host
                        is SessionTab.Sftp -> tab.host
                    }
                    val statusColor = (tab as? SessionTab.Terminal)?.let {
                        statusColor(it.controller.status, it.controller.linkLostSeconds)
                    }
                    // 断开/失败的会话 tab 置灰（状态点保留：灰点=已断开，红点=失败）
                    val inactive = (tab as? SessionTab.Terminal)?.let {
                        val st = it.controller.status
                        st != ConnStatus.CONNECTED && st != ConnStatus.CONNECTING && st != ConnStatus.AUTH
                    } ?: false
                    SessionTabChip(
                        host = host,
                        statusDotColor = statusColor,
                        inactive = inactive,
                        selected = tab.id == current.id,
                        foreground = barForeground,
                        onClick = { onSwitch(tab) },
                        onClose = { onClose(tab) },
                        onPositioned = { x, w -> chipLayout[tab.id] = x to w },
                    )
                }
                }
            }
            Box {
                IconButton(onClick = { addOpen = true }) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = s.hostsAdd,
                        tint = barForeground,
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
                    DropdownMenuItem(
                        text = { Text(s.hostsConnectSftp) },
                        leadingIcon = { Icon(Icons.Filled.FolderOpen, null) },
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
    host: Host,
    statusDotColor: Color?,
    /** 断开/失败的会话：文字与背景降透明度（状态点仍保留区分）。 */
    inactive: Boolean = false,
    selected: Boolean,
    foreground: Color,
    onClick: () -> Unit,
    onClose: () -> Unit,
    onPositioned: (x: Float, width: Float) -> Unit,
) {
    val sys = host.system.ifBlank { host.hostname }
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) foreground.copy(alpha = if (inactive) 0.09f else 0.18f)
                else foreground.copy(alpha = if (inactive) 0.03f else 0.07f),
            )
            .clickable(onClick = onClick)
            .padding(start = 8.dp, top = 5.dp, bottom = 5.dp, end = 2.dp)
            .onGloballyPositioned { coords ->
                onPositioned(coords.positionInRoot().x, coords.size.width.toFloat())
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 系统小头像
        Box(
            Modifier.size(18.dp).clip(RoundedCornerShape(5.dp)).background(systemColor(sys)),
            contentAlignment = Alignment.Center,
        ) {
            SystemAvatarIcon(sys, 11.dp)
        }
        Spacer(Modifier.size(6.dp))
        Text(
            "${host.username}@${host.hostname}",
            style = MaterialTheme.typography.labelSmall,
            color = if (inactive) foreground.copy(alpha = 0.4f) else foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.size(4.dp))
        if (selected && statusDotColor != null) {
            Box(
                Modifier.size(8.dp).clip(CircleShape).background(statusDotColor),
            )
        }
        IconButton(onClick = onClose, modifier = Modifier.size(22.dp)) {
            Icon(
                Icons.Filled.Close,
                contentDescription = null,
                tint = foreground.copy(alpha = 0.7f),
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

/** herdr 引导安装卡片：画布中央展示，远端未安装时引导安装 + 实时安装日志。
 *  @param bottomInset 底部让出高度（工具栏），卡片在其中居中以保持视觉重心。 */
@Composable
private fun HerdrInstallGuide(controller: TerminalController, bottomInset: Dp) {    val s = LocalAppStrings.current
    Box(
        Modifier
            .fillMaxSize()
            .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 24.dp + bottomInset),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    Icons.Filled.Terminal,
                    contentDescription = null,
                    modifier = Modifier.size(44.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    s.herdrMissing,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Text(
                    s.herdrInstallHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                if (controller.herdrInstalling) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(
                            s.herdrInstalling,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // 安装实时日志：显示最后 8 行，挂住时可见无新进展
                    val log = controller.herdrInstallLog
                    if (log.isNotBlank()) {
                        Text(
                            log.lines().takeLast(8).joinToString("\n"),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = monospaceFontFamily(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 8,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(10.dp),
                        )
                    }
                } else {
                    Button(onClick = { controller.installHerdr() }) {
                        Text(s.herdrInstall)
                    }
                }
            }
        }
    }
}

/** Mosh 引导安装卡片：远端未装 mosh-server 时引导安装（包管理器），可降级 SSH。
 *  @param bottomInset 底部让出高度（工具栏），卡片在其中居中以保持视觉重心。 */
@Composable
private fun MoshInstallGuide(controller: TerminalController, bottomInset: Dp) {
    val s = LocalAppStrings.current
    Box(
        Modifier
            .fillMaxSize()
            .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 24.dp + bottomInset),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    Icons.Filled.Link,
                    contentDescription = null,
                    modifier = Modifier.size(44.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    s.moshMissing,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Text(
                    s.moshInstallHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                if (controller.moshInstalling) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(
                            s.moshInstalling,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // 安装实时日志：显示最后 8 行，挂住时可见无新进展
                    val log = controller.moshInstallLog
                    if (log.isNotBlank()) {
                        Text(
                            log.lines().takeLast(8).joinToString("\n"),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = monospaceFontFamily(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 8,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(10.dp),
                        )
                    }
                } else {
                    // sudo 需要密码：输入框（密码仅本次安装使用，经加密 SSH 通道传输）
                    val sudoPwd = remember { mutableStateOf("") }
                    if (controller.moshNeedsSudoPassword) {
                        OutlinedTextField(
                            value = sudoPwd.value,
                            onValueChange = { sudoPwd.value = it },
                            label = { Text(s.moshSudoPasswordLabel) },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            s.moshSudoPasswordHint,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Button(
                        onClick = {
                            controller.installMosh(
                                if (controller.moshNeedsSudoPassword) sudoPwd.value else null,
                            )
                        },
                        enabled = !controller.moshNeedsSudoPassword || sudoPwd.value.isNotBlank(),
                    ) {
                        Text(s.moshInstall)
                    }
                    // 降级选项：用户可选择不安装，继续用 SSH
                    TextButton(onClick = { controller.degradeMoshToSsh() }) {
                        Text(s.moshDegradeToSsh)
                    }
                }
            }
        }
    }
}

/** 顶部浮层错误/状态 banner：直角横幅盖在画布上（不占布局空间、不把画布顶下来）。 */
@Composable
private fun ErrorBanner(
    message: String,
    isError: Boolean,
    onDismiss: (() -> Unit)?,
    onReconnect: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val s = LocalAppStrings.current
    val color = if (isError) StatusColors.Error else StatusColors.Warning
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .background(color.copy(alpha = 0.16f))
            .border(1.dp, color.copy(alpha = 0.35f))
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            message,
            color = color,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f).padding(vertical = 8.dp),
        )
        if (onReconnect != null) {
            TextButton(onClick = onReconnect) {
                Text(s.terminalReconnect, color = color, style = MaterialTheme.typography.labelMedium)
            }
        }
        if (onDismiss != null) {
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.Close, contentDescription = s.settingsClose, tint = color, modifier = Modifier.size(16.dp))
            }
        }
    }
}

package dev.termish.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Monitor
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.termish.data.Host
import dev.termish.screen.ScreenControlPacket
import dev.termish.screen.ScreenSession
import dev.termish.screen.ScreenUiState
import dev.termish.screen.ScreenVideoSurface
import dev.termish.ui.theme.ScreenControlDimens
import dev.termish.ui.theme.ScreenControlTokens
import dev.termish.ui.theme.Sizes
import dev.termish.ui.theme.StatusColors
import dev.termish.util.TermLog
import dev.termish.util.monospaceFontFamily
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * 屏幕 tab（远程画面）：全屏播放 H.264 推流，黑底 + Fit 缩放；
 * 右上角常驻帧率/分辨率角标；连接中/错误态与终端页同款。
 */
@Composable
fun ScreenContent(
    host: Host,
    session: ScreenSession?,
    state: ScreenUiState,
    onBack: () -> Unit,
    onReconnect: () -> Unit,
    /** 服务缺失时的一键安装（引导卡片按钮）。 */
    onInstallService: (String?) -> Unit = { _ -> },
    /** 就地全屏模式：左上角返回按钮显示「收起」（不跳 tab）；否则显示「返回」。 */
    onClose: (() -> Unit)? = null,
    /** 状态栏高度（px，沉浸式隐藏前记录）：header 内容下移，返回按钮与终端页
     * tab 栏对齐（用户反馈：全屏返回按钮比终端页更靠上）。 */
    statusBarInsetTop: Int = 0,
    /** 推流参数切换（帧率/画质）：写远端配置后重建会话生效。 */
    onStreamConfigChange: (fps: Int, scale: String) -> Unit = { _, _ -> },
    /** 帧率档位本地更新（异步重建前 UI 先反馈）。 */
    onFpsIndex: (Int) -> Unit = {},
    /** 视频面是否上真 SurfaceView（展开动画期间 false，黑底占位——动画把整个
     * 容器从小窗尺寸 graphicsLayer 缩放到全屏，部分设备视频表面不跟随缩放，
     * 画面卡成小窗尺寸的「亮点」/黑屏；动画结束后再建面，surface 永远全尺寸） */
    videoEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val s = LocalAppStrings.current
    val density = LocalDensity.current
    val keyboardController = LocalSoftwareKeyboardController.current
    // 远程键盘工具栏（右下角 ⌨ 按钮展开）：软键盘 + 一行功能键。
    // 布局：画面(weight 1f) + 工具栏 + 隐藏输入框——imePadding 把工具栏
    // 顶到键盘上方，工具栏顶部 = 画面底部（用户要求：不覆盖画面）
    var keyboardOpen by remember { mutableStateOf(false) }
    val keyFocus = remember { FocusRequester() }
    // 键盘完全展开时的高度（固定值）：收起动画期间 Spacer 不渐变，
    // 视频不随动画下移再跳回（用户反馈：先下来再上去）
    var imeHeightFixed by remember { mutableStateOf(0) }
    // 预取（WindowInsets.ime 是 @Composable 属性，LaunchedEffect 里不能直接访问）
    val imeInsets = WindowInsets.ime
    // 系统键盘可见性监听：软键盘自带的收起按钮/手势收起键盘时（ime 高度归零），
    // 同步关闭工具栏回到初始态（否则工具栏残留 + 视频跳位——用户反馈）
    val imeHeightForSync = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(imeHeightForSync) {
        if (keyboardOpen && imeHeightForSync <= 0) {
            keyboardOpen = false
        }
    }
    // 零宽空格哨兵：TextField 永不真正为空——软键盘退格在空文本时无文本
    // 变化、onValueChange 不触发（Mac 输入框原有字符删不掉——用户反馈）。
    // 哨兵让退格永远有内容可删：删哨兵 → 转发 BACKSPACE + 立即恢复
    // ⚠️ selection 必须在哨兵后（TextRange(1)）：TextFieldValue 默认光标在开头，
    // 新输入会插到哨兵前，增量截取末尾 → 每次发送的都是哨兵（用户看到"发了个空格"）
    var keyState by remember { mutableStateOf(TextFieldValue(KEY_SENTINEL, TextRange(KEY_SENTINEL.length))) }
    // IME 组合开始前的文本（组合结束对比它取上屏增量——组合中 keyState
    // 已更新为组合文本，直接 diff 会丢失上屏内容：单字母/首汉字被吞）
    var preComposeText by remember { mutableStateOf("") }
    // 粘性修饰键（⌘/⌃/⌥/⇧ 点按激活，随下一个键发出后保持；再点取消）
    var activeMods by remember { mutableStateOf(0) }
    var capsOn by remember { mutableStateOf(false) }
    // 全屏手势（单指平移 + 双指缩放桌面）：zoomScale 放大倍数、panOffset 画面左上角偏移
    var zoomScale by remember { mutableStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var videoViewportSize by remember { mutableStateOf(IntSize.Zero) }
    var virtualMouseOpen by remember { mutableStateOf(false) }
    var rightControlAreaPx by remember { mutableFloatStateOf(0f) }
    // 未被右缘钳制的累积 anchor X（NaN=未初始化，首次拖拽从当前 anchor 同步）：
    // 拖过右缘的过冲量必须持久化，否则 overlap 每帧只剩当次 delta，视频推不开
    // （用户反馈：拖到右缘卡住，光标点不到视频最右列——对齐 ToDesk 整体左移效果）
    var virtualMouseRawX by remember { mutableFloatStateOf(Float.NaN) }
    var virtualMouseAnchorPosition by remember { mutableStateOf<Offset?>(null) }
    var virtualMouseLeftHeld by remember { mutableStateOf(false) }
    var virtualMouseHeldCursor by remember { mutableStateOf<ScreenPoint?>(null) }

    val mousePanelWidthPx = with(density) { ScreenControlDimens.MousePanelWidth.toPx() }
    val mousePanelHeightPx = with(density) { ScreenControlDimens.MousePanelHeight.toPx() }
    val mouseCursorPanelOffsetXPx = with(density) { ScreenControlDimens.MouseCursorPanelOffsetX.toPx() }
    val mouseCursorPanelOffsetYPx = with(density) { ScreenControlDimens.MouseCursorPanelOffsetY.toPx() }
    val mouseCloseSpacePx =
        with(density) {
            (ScreenControlDimens.MouseCloseGap + ScreenControlDimens.MouseCloseButton).toPx()
        }
    val mouseControlWidthPx = mouseCursorPanelOffsetXPx + mousePanelWidthPx + mouseCloseSpacePx
    val mouseControlHeightPx = mouseCursorPanelOffsetYPx + mousePanelHeightPx
    val mousePanelTopInsetPx = statusBarInsetTop + with(density) { Sizes.HeaderCompact.toPx() }
    // rightControlAreaPx is now a state updated in onMovePanel (gradual push)

    fun releaseVirtualMouseLeft() {
        if (!virtualMouseLeftHeld) return
        virtualMouseHeldCursor?.let { cursor ->
            state.controlSender?.invoke(
                ScreenControlPacket.TYPE_VIRTUAL_LEFT_UP,
                cursor.x,
                cursor.y,
                0,
            )
        }
        virtualMouseLeftHeld = false
        virtualMouseHeldCursor = null
        TermLog.i("screen") { "virtual mouse left drag ended" }
    }

    fun closeVirtualMouse() {
        releaseVirtualMouseLeft()
        if (!virtualMouseOpen) return
        virtualMouseOpen = false
        rightControlAreaPx = 0f
        virtualMouseRawX = Float.NaN
        virtualMouseAnchorPosition = null
        TermLog.i("screen") { "virtual mouse closed" }
    }

    LaunchedEffect(state.controlMode, state.connected) {
        if (!state.controlMode || !state.connected) closeVirtualMouse()
    }

    // 点击涟漪（远程操作反馈）：按下点扩散圆圈动画，ToDesk/向日葵同款
    data class RippleFx(
        val x: Float,
        val y: Float,
        val id: Int,
    )

    var ripple by remember { mutableStateOf<RippleFx?>(null) }
    var rippleSeq by remember { mutableIntStateOf(0) }
    val rippleProgress = remember { Animatable(0f) }
    LaunchedEffect(ripple?.id) {
        if (ripple != null) {
            rippleProgress.snapTo(0f)
            rippleProgress.animateTo(1f, animationSpec = tween(450))
        }
    }

    /**
     * 触摸点 → 归一化画面坐标（0-1）：视频按 Fit 居中显示（letterbox），
     * 叠加本地缩放/平移后即为画面实际显示区域——操作模式下按此映射，
     * 任意推流分辨率/缩放态下与远端屏幕坐标一致。
     */

    fun screenFrame(
        st: ScreenUiState,
        controlAreaPx: Float = rightControlAreaPx,
    ): ScreenRect {
        val size = videoViewportSize.takeIf { it.width > 0 && it.height > 0 } ?: viewportSize
        val vw = size.width.toFloat()
        val vh = size.height.toFloat()
        val dims = st.player?.videoDims?.value
        val dw = dims?.first?.toFloat() ?: vw
        val dh = dims?.second?.toFloat() ?: vh
        return fittedScreenRect(
            containerWidth = vw,
            containerHeight = vh,
            videoWidth = dw,
            videoHeight = dh,
            zoom = zoomScale,
            panX = panOffset.x - controlAreaPx,
            panY = panOffset.y,
            alignBottom = keyboardOpen && imeHeightFixed > 0,
        )
    }

    fun toScreenNorm(
        p: Offset,
        st: ScreenUiState,
    ): Pair<Float, Float> {
        val frame = screenFrame(st)
        if (frame.width <= 0f || frame.height <= 0f) return 0f to 0f
        val nx = (p.x - frame.left) / frame.width
        val ny = (p.y - frame.top) / frame.height
        return nx.coerceIn(0f, 1f) to ny.coerceIn(0f, 1f)
    }

    LaunchedEffect(
        virtualMouseOpen,
        viewportSize,
        videoViewportSize,
        state.player?.videoDims?.value,
        zoomScale,
        panOffset,
    ) {
        if (!virtualMouseOpen || viewportSize.width <= 0 || viewportSize.height <= 0) return@LaunchedEffect
        val frame = screenFrame(state)
        val current = virtualMouseAnchorPosition
        val proposed =
            when {
                rightControlAreaPx > 0f && current != null -> ScreenPoint(frame.right, current.y)
                current != null -> ScreenPoint(current.x, current.y)
                else ->
                    ScreenPoint(
                        x = (viewportSize.width - mouseControlWidthPx) / 2f,
                        y = (viewportSize.height - mouseControlHeightPx) / 2f,
                    )
            }
        val clamped =
            clampVirtualMouseAnchor(
                proposed = proposed,
                frame = frame,
                viewportHeight = viewportSize.height.toFloat(),
                controlHeight = mouseControlHeightPx,
                topInset = mousePanelTopInsetPx,
                // 与 onMovePanel 一致：面板右边缘贴视口右缘（完整可见，放大态
                // 同样如此），避免 effect 重启时 anchor 跳变导致面板闪动
                viewportWidth =
                    (viewportSize.width.toFloat() - mouseControlWidthPx).coerceAtLeast(0f),
            )
        virtualMouseAnchorPosition = Offset(clamped.x, clamped.y)
        // 重钳制后回同步 rawX：保持不变式 overlap = rawX - maxAnchorX，
        // 否则 effect 重启（旋转/首帧尺寸上报）后第一次拖拽 rawX 漂移、视频跳变
        virtualMouseRawX = clamped.x + rightControlAreaPx
    }
    Column(
        modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .onSizeChanged { viewportSize = it },
        ) {
            // 右侧停靠时只把原尺寸画面整体左移并裁掉左侧，播放器容器宽度不变，
            // 因而不会重新 Fit 缩小；腾出的右侧黑区专门放虚拟鼠标。
            // ⚠️ 容器保持全宽且不加 clipToBounds：左移后越界的部分由屏幕边界自然
            // 裁剪。「被祖先 clip 的 SurfaceView」在部分设备上表面内容会被缩放而
            // 不是裁剪（用户反馈：推右时画面跟着缩小）——屏幕边界裁 SurfaceView
            // 是所有设备上最成熟的路径。
            val fullVideoWidthPx = viewportSize.width.coerceAtLeast(1).toFloat()
            val visibleVideoWidthPx = (fullVideoWidthPx - rightControlAreaPx).coerceAtLeast(1f)
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .fillMaxHeight()
                    .width(with(density) { fullVideoWidthPx.toDp() }),
            ) {
                Box(
                    Modifier
                        .offset { IntOffset(-rightControlAreaPx.roundToInt(), 0) }
                        .fillMaxHeight()
                        .width(with(density) { fullVideoWidthPx.toDp() })
                        .onSizeChanged { videoViewportSize = it },
                ) {
                    if (videoEnabled) {
                        state.player?.let { p ->
                            ScreenVideoSurface(
                                p,
                                Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        // 以左上角为缩放原点：translation 即画面左上角偏移，钳制边界与 panOffset 语义一致
                                        transformOrigin = TransformOrigin(0f, 0f)
                                        scaleX = zoomScale
                                        scaleY = zoomScale
                                        translationX = panOffset.x
                                        translationY = panOffset.y
                                    },
                                // 键盘完全到位后视频才贴底（与 Spacer 同步，避免弹出动画
                                // 期间先下拉再顶起——用户反馈）
                                alignBottom = keyboardOpen && imeHeightFixed > 0,
                            )
                        }
                    }
                }
            }

            // 全屏手势层：观看模式 = 单指平移 + 双指缩放本地视图；
            // 操作模式 = 触摸→鼠标（单指左键/拖动、双指滚轮）。
            // 先于连接中/错误态/header 声明：这些覆盖层后声明、优先命中触摸，
            // 手势层只接收它们未覆盖区域的触摸（正常播放时即整个画面）。
            if (onClose != null) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(with(density) { visibleVideoWidthPx.toDp() })
                        // ⚠️ key 必须同时包含 state 实例与 controlMode：切画质会重建
                        // ScreenSession/ScreenUiState。如果手势协程不重启，它会继续
                        // 捕获旧 state，把触摸发到已关闭的 controlSender；虚拟鼠标
                        // 回调经重组后却正常，因而表现为“手指失效、鼠标正常”。
                        .pointerInput(state, state.controlMode, videoViewportSize) {
                            if (state.controlMode) {
                                // 远程操作：单指 = 鼠标左键（按下/拖动/抬起），双指垂直滑动 = 滚轮。
                                // 坐标归一化到画面显示区域；事件不经队列即时发（丢包容忍）
                                val slop = viewConfiguration.touchSlop
                                awaitEachGesture {
                                    val down = awaitFirstDown()
                                    val sender = state.controlSender
                                    // 点击涟漪反馈（ToDesk 同款）：按下点扩散圆圈
                                    val rippleId = (rippleSeq++)
                                    ripple = RippleFx(down.position.x, down.position.y, rippleId)
                                    var multi = false
                                    var moved = false
                                    var lastPos = down.position
                                    var lastN = toScreenNorm(down.position, state)
                                    // 双指手势状态：捏合距离（本地缩放）+ 质心垂直位移（滚轮）
                                    var prevDist = -1f
                                    var prevCentroid = Offset.Zero
                                    var scrollAccum = 0f
                                    // 按下即左键 down（轻点 = down+up 点击；滑动 = 拖动画布，
                                    // 不模拟鼠标移动——用户手势定义：单指滑动就是滑动）
                                    sender?.invoke(ScreenControlPacket.TYPE_DOWN, lastN.first, lastN.second, 0)
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val pressed = event.changes.filter { it.pressed }
                                        if (pressed.isEmpty()) {
                                            // 全部抬起 = 左键 up（轻点 = 点击；滑动抬起无副作用）
                                            sender?.invoke(ScreenControlPacket.TYPE_UP, lastN.first, lastN.second, 0)
                                            break
                                        }
                                        if (pressed.size >= 2) {
                                            multi = true
                                            // 双指：捏合 = 本地放大（质心锚定）；距离稳定时
                                            // 质心垂直滑动 = 滚轮（50px 一行）
                                            val p1 = pressed[0].position
                                            val p2 = pressed[1].position
                                            val dist = (p2 - p1).getDistance()
                                            val centroid = (p1 + p2) / 2f
                                            if (prevDist > 0f) {
                                                val factor = dist / prevDist
                                                // 阈值 0.03：手指自然抖动（<3%）不算捏合
                                                if (abs(factor - 1f) > 0.03f) {
                                                    // 捏合 → 本地缩放：放大后点击坐标经
                                                    // toScreenNorm 自动映射（含 zoom/pan），
                                                    // 精确点小按钮（用户反馈：操作中太小）
                                                    val old = zoomScale
                                                    val new = (old * factor).coerceIn(1f, MAX_SCREEN_ZOOM)
                                                    if (new != old) {
                                                        panOffset = centroid - (centroid - panOffset) * (new / old)
                                                        zoomScale = new
                                                        if (videoViewportSize.width > 0 && videoViewportSize.height > 0) {
                                                            val maxX = videoViewportSize.width * (zoomScale - 1f)
                                                            val maxY = videoViewportSize.height * (zoomScale - 1f)
                                                            panOffset =
                                                                Offset(
                                                                    panOffset.x.coerceIn(-maxX, 0f),
                                                                    panOffset.y.coerceIn(-maxY, 0f),
                                                                )
                                                        }
                                                    }
                                                    scrollAccum = 0f
                                                } else {
                                                    // 质心垂直位移 → 滚轮
                                                    scrollAccum += centroid.y - prevCentroid.y
                                                    val delta = (scrollAccum / 50f).toInt()
                                                    if (delta != 0) {
                                                        sender?.invoke(ScreenControlPacket.TYPE_SCROLL, lastN.first, lastN.second, -delta)
                                                        scrollAccum -= delta * 50f
                                                    }
                                                }
                                            }
                                            prevDist = dist
                                            prevCentroid = centroid
                                            event.changes.forEach { it.consume() }
                                        } else {
                                            // 单指：slop 后 = 拖动画布（平移视口，放大后看
                                            // 别处；zoom=1 时钳制归零自然无效）。不模拟鼠标
                                            // 移动（用户手势定义：单指就是滑动）
                                            if (multi) {
                                                multi = false
                                                moved = false
                                                lastPos = pressed[0].position
                                            }
                                            val p = pressed[0].position
                                            val d = p - lastPos
                                            if (!moved && d.getDistance() > slop) {
                                                moved = true
                                                lastPos = p
                                            }
                                            if (moved) {
                                                panOffset =
                                                    Offset(
                                                        panOffset.x + d.x,
                                                        panOffset.y + d.y,
                                                    )
                                                if (videoViewportSize.width > 0 && videoViewportSize.height > 0) {
                                                    val maxX = videoViewportSize.width * (zoomScale - 1f)
                                                    val maxY = videoViewportSize.height * (zoomScale - 1f)
                                                    panOffset =
                                                        Offset(
                                                            panOffset.x.coerceIn(-maxX, 0f),
                                                            panOffset.y.coerceIn(-maxY, 0f),
                                                        )
                                                }
                                                lastPos = p
                                                pressed[0].consume()
                                            }
                                        }
                                    }
                                }
                            } else {
                                detectTransformGestures(panZoomLock = true) { centroid, pan, zoom, _ ->
                                    val old = zoomScale
                                    val new = (old * zoom).coerceIn(1f, MAX_SCREEN_ZOOM)
                                    val effective = new / old
                                    // 围绕双指质心缩放 + 平移增量：质心处画面内容保持不动
                                    panOffset = centroid - (centroid - panOffset) * effective + pan
                                    zoomScale = new
                                    // 钳制在视口内：放大后画面只能平移到边界，scale=1 时归零
                                    if (videoViewportSize.width > 0 && videoViewportSize.height > 0) {
                                        val maxX = videoViewportSize.width * (zoomScale - 1f)
                                        val maxY = videoViewportSize.height * (zoomScale - 1f)
                                        panOffset =
                                            Offset(
                                                panOffset.x.coerceIn(-maxX, 0f),
                                                panOffset.y.coerceIn(-maxY, 0f),
                                            )
                                    }
                                }
                            }
                        },
                )
            }

            // 点击涟漪层（远程操作模式）：按下位置的扩散圆环，随动画淡出。
            // 声明于画面/手势层之后（覆盖其上）、错误态/header 之前
            if (state.controlMode) {
                ripple?.let { r ->
                    Canvas(Modifier.fillMaxSize()) {
                        val p = rippleProgress.value
                        val radius = 14.dp.toPx() * (0.3f + p * 1.2f)
                        drawCircle(
                            color = Color.White.copy(alpha = (1f - p) * 0.85f),
                            radius = radius,
                            center = Offset(r.x, r.y),
                            style = Stroke(width = 2.dp.toPx()),
                        )
                    }
                }
            }

            // 连接中：居中指示器（含已连通但首帧未到的等待态；全屏模式 session 为 null 也显示）
            if (session == null || !state.connected || !state.videoReady) {
                ConnectingIndicator(
                    visible = state.error == null && !state.videoReady,
                    text = s.screen.connecting,
                    containerColor = Color(0xFF101216),
                    contentColor = Color.White,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            // 服务缺失：引导一键安装（含具体原因 + 安装日志）。此时 uiState.error
            // 也被设置——不再渲染通用错误态，避免红色错误文案 + 「重新连接」按钮
            // 与「安装」操作重复（v1.5.0 用户反馈）
            if (state.serviceMissing) {
                ScreenServiceGuide(
                    installing = state.installing,
                    installLog = state.installLog,
                    ffmpegMissing = state.ffmpegMissing,
                    needsSudoPassword = state.needsSudoPassword,
                    onInstall = onInstallService,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                // 错误态：卡片（与安装引导同风格：图标 + 文案 + 主/次按钮）
                state.error?.let { msg ->
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Card(
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                Modifier.fillMaxWidth().padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                // 错误图标：错误色浅底圆角容器（与安装引导同款）
                                Box(
                                    Modifier
                                        .size(56.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(StatusColors.Error.copy(alpha = 0.12f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Filled.ErrorOutline,
                                        contentDescription = null,
                                        modifier = Modifier.size(28.dp),
                                        tint = StatusColors.Error,
                                    )
                                }
                                Text(
                                    msg,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    textAlign = TextAlign.Center,
                                )
                                if (state.ffmpegMissing) {
                                    Text(
                                        s.screen.ffmpegHint,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                                Button(
                                    onClick = onReconnect,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(s.screen.reconnect)
                                }
                                TextButton(onClick = onBack) {
                                    Text(
                                        s.terminalCancel,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 屏幕状态提示（息屏/锁屏，可恢复）：画面中央下方半透明小字，
            // 画面到达自动清除（ScreenSession onReady）
            state.screenHint?.let { hint ->
                Text(
                    hint,
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodySmall,
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }

            // 被控端控制状态提示：Linux 控制后端不可用 / macOS 缺辅助功能权限。
            if (state.controlMode && state.controlUnsupported) {
                Text(
                    s.screen.controlUnsupportedHint,
                    color = Color.White.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 48.dp, start = 24.dp, end = 24.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFFB45309).copy(alpha = 0.85f))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            } else if (state.controlMode && state.controlPermissionMissing) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = ScreenControlDimens.PermissionBannerSide)
                            .padding(bottom = ScreenControlDimens.PermissionBannerBottom)
                            .clip(RoundedCornerShape(ScreenControlDimens.PermissionBannerCorner))
                            .background(ScreenControlTokens.PermissionBackground)
                            .padding(
                                horizontal = ScreenControlDimens.PermissionBannerHorizontal,
                                vertical = ScreenControlDimens.PermissionBannerVertical,
                            ),
                ) {
                    Text(
                        s.screen.controlPermissionHint,
                        color = ScreenControlTokens.PermissionText,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = onReconnect) {
                        Text(s.screen.reconnect, color = ScreenControlTokens.PermissionText)
                    }
                }
            }

            if (virtualMouseOpen && state.controlMode) {
                val frame = screenFrame(state)
                virtualMouseAnchorPosition?.let { anchor ->
                    val cursor =
                        cursorAtVirtualMouseAnchor(
                            anchor = ScreenPoint(anchor.x, anchor.y),
                            frame = frame,
                        )
                    val moveVirtualMouse: (Offset) -> ScreenPoint? = { delta ->
                        val current = virtualMouseAnchorPosition
                        if (current == null) {
                            null
                        } else {
                            val viewportRight = viewportSize.width.toFloat()
                            val maxAnchorX =
                                (viewportRight - mouseControlWidthPx).coerceAtLeast(0f)
                            val unpushedFrame = screenFrame(state, 0f)
                            // rawX 全程累加 delta（不被右缘 clamp 吃掉）：超过 maxAnchorX
                            // 的部分 = 视频左移量。推量上限 = 把画面右缘推到面板左缘所需
                            // 的平移量：未放大时恰为面板宽；放大后画面右缘在视口外、上限
                            // 自动变大——鼠标贴近右边时画面持续平移过来，鼠标永不滑出屏幕
                            val rawX =
                                (if (virtualMouseRawX.isNaN()) current.x else virtualMouseRawX) +
                                    delta.x
                            // 左/右边界渐进推开（对称）：面板拖过画面边缘的过冲量，
                            // 转成画面反向平移——右推=画面左移（右边内容挤进来），
                            // 左推=画面右移（放大后左边隐藏内容挤出来）
                            val leftEdge = unpushedFrame.left.coerceAtLeast(0f)
                            val overLeft = leftEdge - rawX
                            val anchorX: Float
                            val overlapPx: Float
                            if (overLeft > 0f && panOffset.x < 0f) {
                                val maxPanX =
                                    (videoViewportSize.width * (zoomScale - 1f)).coerceAtLeast(0f)
                                panOffset =
                                    Offset(
                                        (panOffset.x + overLeft).coerceIn(-maxPanX, 0f),
                                        panOffset.y,
                                    )
                                virtualMouseRawX = leftEdge
                                anchorX = leftEdge
                                overlapPx = 0f
                            } else {
                                val push =
                                    computeVirtualMousePush(
                                        rawX,
                                        viewportRight,
                                        mouseControlWidthPx,
                                        unpushedFrame.right,
                                    )
                                virtualMouseRawX =
                                    rawX.coerceIn(
                                        leftEdge,
                                        maxAnchorX + (unpushedFrame.right - maxAnchorX).coerceAtLeast(0f),
                                    )
                                anchorX = push.anchorX
                                overlapPx = push.overlapPx
                            }
                            rightControlAreaPx = overlapPx
                            val targetFrame = screenFrame(state, overlapPx)
                            val clamped =
                                clampVirtualMouseAnchor(
                                    proposed = ScreenPoint(anchorX, current.y + delta.y),
                                    frame = targetFrame,
                                    viewportHeight = viewportSize.height.toFloat(),
                                    controlHeight = mouseControlHeightPx,
                                    topInset = mousePanelTopInsetPx,
                                    viewportWidth = maxAnchorX,
                                )
                            virtualMouseAnchorPosition = Offset(clamped.x, clamped.y)
                            cursorAtVirtualMouseAnchor(clamped, targetFrame)
                        }
                    }
                    VirtualMouseCursor(
                        frame = frame,
                        cursor = cursor,
                    )
                    VirtualMousePanel(
                        position =
                            Offset(
                                anchor.x + mouseCursorPanelOffsetXPx,
                                anchor.y + mouseCursorPanelOffsetYPx,
                            ),
                        strings = s.screen,
                        onClose = ::closeVirtualMouse,
                        onLeftClick = {
                            state.controlSender?.invoke(
                                ScreenControlPacket.TYPE_CLICK,
                                cursor.x,
                                cursor.y,
                                0,
                            )
                            val hotspot = cursorHotspot(cursor, screenFrame(state))
                            ripple = RippleFx(hotspot.x, hotspot.y, rippleSeq++)
                            TermLog.i("screen") { "virtual mouse left click" }
                        },
                        onLeftDragStart = {
                            if (!virtualMouseLeftHeld) {
                                virtualMouseLeftHeld = true
                                virtualMouseHeldCursor = cursor
                                state.controlSender?.invoke(
                                    ScreenControlPacket.TYPE_VIRTUAL_LEFT_DOWN,
                                    cursor.x,
                                    cursor.y,
                                    0,
                                )
                                TermLog.i("screen") { "virtual mouse left drag started" }
                            }
                        },
                        onLeftDrag = { delta ->
                            moveVirtualMouse(delta)?.let { movedCursor ->
                                virtualMouseHeldCursor = movedCursor
                                state.controlSender?.invoke(
                                    ScreenControlPacket.TYPE_VIRTUAL_LEFT_DRAG,
                                    movedCursor.x,
                                    movedCursor.y,
                                    0,
                                )
                            }
                        },
                        onLeftDragEnd = ::releaseVirtualMouseLeft,
                        onRightClick = {
                            state.controlSender?.invoke(
                                ScreenControlPacket.TYPE_RIGHT_CLICK,
                                cursor.x,
                                cursor.y,
                                0,
                            )
                            TermLog.i("screen") { "virtual mouse right click" }
                        },
                        onScroll = { direction ->
                            state.controlSender?.invoke(
                                ScreenControlPacket.TYPE_SCROLL,
                                cursor.x,
                                cursor.y,
                                direction,
                            )
                            TermLog.i("screen") { "virtual mouse scroll direction=$direction" }
                        },
                        onMovePanel = { delta ->
                            moveVirtualMouse(delta)?.let { movedCursor ->
                                if (virtualMouseLeftHeld) {
                                    virtualMouseHeldCursor = movedCursor
                                    state.controlSender?.invoke(
                                        ScreenControlPacket.TYPE_VIRTUAL_LEFT_DRAG,
                                        movedCursor.x,
                                        movedCursor.y,
                                        0,
                                    )
                                }
                            }
                        },
                    )
                }
            }

            // 全屏头部栏：与终端页 tab 栏同款（半透明黑底 + 返回 IconButton + 主机名 +
            // 帧率角标），返回按钮不再裸露贴顶（用户反馈：应与终端页返回按钮同高度，
            // 全屏应有头部）。⚠️ 最后声明（最顶层）：视频面/错误态都是 fillMaxSize
            // 覆盖层，先声明会被盖住（v1.4.0 回归：看不到也点不到）。
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .zIndex(100f)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 4.dp, vertical = 4.dp)
                    // 内容下移到状态栏高度（背景铺满顶部）：返回按钮与终端页 tab 栏同高度
                    .padding(top = with(density) { statusBarInsetTop.toDp() }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { (onClose ?: onBack)() }) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = if (onClose != null) s.screen.collapse else s.screen.back,
                        tint = Color.White.copy(alpha = 0.9f),
                    )
                }
                // 主机名（tab 栏同款标题位置）
                Text(
                    host.name.ifBlank { host.hostname },
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
                // 模式切换：默认操作模式（触摸→鼠标 + 键盘），可切纯观看（无键盘）
                Text(
                    if (state.controlMode) s.screen.controlActive else s.screen.viewMode,
                    color = Color.White.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (state.controlMode) Color(0xFF2563EB).copy(alpha = 0.8f) else Color.Black.copy(alpha = 0.45f),
                            ).padding(horizontal = 8.dp, vertical = 5.dp)
                            .clickable {
                                // ⚠️ 不重置本地缩放：用户放大后切操作模式应保持放大
                                // 状态（之前强制 zoom=1，用户反馈「一开始就是放大的，
                                // 点操作又变小了」）
                                state.controlMode = !state.controlMode
                            },
                )
                // 帧率/画质切换按钮（右上角）：点击弹菜单选档位，
                // 生效方式 = 写远端 relay 配置 + 重建推流会话
                StreamQualitySwitcher(
                    fps = state.streamFps,
                    quality = state.streamQuality,
                    videoDims = state.player?.videoDims?.value,
                    maxFps = state.decoderMaxFps,
                    deliveredFps = state.fps,
                    bitrateKbps = state.bitrateKbps,
                    jitterMillis = state.jitterMillis,
                    droppedPermille = state.droppedPermille,
                    onSelect = onStreamConfigChange,
                    onFpsIndex = { state.streamFps = it },
                    onQualityIndex = { state.streamQuality = it },
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            // 键盘按钮（画面右下角，操作模式 + 键盘收起时显示）：点击弹系统键盘 + 工具栏。
            // navigationBarsPadding：避开屏幕底部导航栏（手势条）遮挡。
            // 观看模式无键盘（纯看）
            if (!keyboardOpen && state.controlMode) {
                Column(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(ScreenControlDimens.FloatingMargin),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (!virtualMouseOpen) {
                        VirtualMouseButton(
                            contentDescription = s.screen.virtualMouse,
                            onClick = {
                                virtualMouseOpen = true
                                rightControlAreaPx = 0f
                                TermLog.i("screen") { "virtual mouse opened" }
                            },
                        )
                        Spacer(Modifier.height(ScreenControlDimens.FloatingSpacing))
                    }
                    Box(
                        Modifier
                            .size(ScreenControlDimens.FloatingButton)
                            .clip(RoundedCornerShape(ScreenControlDimens.FloatingButton))
                            .background(ScreenControlTokens.FloatingBackground)
                            .clickable {
                                closeVirtualMouse()
                                keyboardOpen = true
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "⌨",
                            color = Color.White,
                            fontSize = 20.sp,
                        )
                    }
                }
            }
        }

        // 远程键盘工具栏（操作模式 + 键盘弹出时显示，在键盘上方；⌨ 固定在最右不随滚动）
        if (keyboardOpen && state.controlMode) {
            RemoteKeyBar(
                activeMods = activeMods,
                capsOn = capsOn,
                onKeyboardToggle = { keyboardOpen = false },
                onModToggle = { mod ->
                    activeMods = if (activeMods and mod != 0) activeMods and mod.inv() else activeMods or mod
                    // 工具栏键点击会抢走 TextField 焦点：立即归还，否则后续
                    // 字母输入无处可去（用户反馈：点过工具栏后输入不生效）
                    keyFocus.requestFocus()
                },
                onCapsToggle = {
                    capsOn = !capsOn
                    state.keySender?.invoke(ScreenControlPacket.KeyCode.CAPS, 0, "")
                    keyFocus.requestFocus()
                },
                onKey = { keyCode, mods ->
                    state.keySender?.invoke(keyCode, mods, "")
                    keyFocus.requestFocus()
                },
            )
            // 隐藏输入框：获得焦点即弹软键盘；onValueChange 差异 → 文本/删除键。
            // IME 组合（中文拼音）期间不发送，上屏（组合结束）发最终文本
            Box(Modifier.fillMaxWidth().height(1.dp)) {
                val keySenderLocal = state.keySender
                BasicTextField(
                    value = keyState,
                    // 关闭自动纠错：Gboard 英文输入不再进入组合态（否则字母
                    // 积压到按空格才上屏——用户反馈输入字母不生效），逐字母实时
                    // 转发；中文拼音组合行为不变（组合态不发送，上屏发最终文本）。
                    // ⚠️ singleLine + imeAction：多行 TextField 的回车是换行（文本
                    // \n）不走 action——用户反馈回车不行；单行后回车 = IME action
                    keyboardOptions =
                        KeyboardOptions(
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Go,
                        ),
                    singleLine = true,
                    // 软键盘回车键：IME action（Go/Done/Next/换行）→ 远端 Return
                    keyboardActions =
                        KeyboardActions(
                            onAny = {
                                keySenderLocal?.invoke(ScreenControlPacket.KeyCode.RETURN, 0, "")
                                true
                            },
                        ),
                    onValueChange = { newState ->
                        val oldState = keyState
                        val composing = newState.composition != null
                        val sender = state.keySender ?: return@BasicTextField
                        val old = oldState.text
                        val new = newState.text
                        when {
                            composing && oldState.composition == null -> {
                                // 组合开始：记录组合前文本（用于组合结束取增量）
                                preComposeText = old
                                keyState = newState
                            }
                            composing -> {
                                // 组合中：拼音中间态不发送，只更新状态
                                keyState = newState
                            }
                            !composing && oldState.composition != null -> {
                                // 组合结束：上屏内容 = 最终文本 - 组合前文本（去哨兵）。
                                // 中文拼音/英文联想组合一次上屏多个字符，逐个 diff 会丢
                                keyState = newState
                                val full = new.removePrefix(KEY_SENTINEL)
                                val pre = preComposeText.removePrefix(KEY_SENTINEL)
                                val committed = if (full.startsWith(pre)) full.removePrefix(pre) else full
                                if (committed.isNotEmpty()) {
                                    TermLog.i("screen") { "key commit: ${committed.length} chars" }
                                    sender(0, 0, committed)
                                    keyState = TextFieldValue(KEY_SENTINEL, TextRange(KEY_SENTINEL.length))
                                }
                            }
                            new.length > old.length -> {
                                // 新增字符（哨兵后追加）：逐字符实时转发（英文不积压）
                                keyState = newState
                                val added = new.substring(old.length)
                                TermLog.i("screen") { "key add: ${added.length} chars" }
                                sender(0, 0, added)
                            }
                            new.length < old.length -> {
                                if (!new.contains(KEY_SENTINEL)) {
                                    // 哨兵被删（空 TextField 按退格）：转发 BACKSPACE + 恢复哨兵
                                    keyState = TextFieldValue(KEY_SENTINEL, TextRange(KEY_SENTINEL.length))
                                    sender(ScreenControlPacket.KeyCode.BACKSPACE, 0, "")
                                } else {
                                    keyState = newState
                                    val n = old.length - new.length
                                    repeat(n) { sender(ScreenControlPacket.KeyCode.BACKSPACE, 0, "") }
                                }
                            }
                            else -> keyState = newState
                        }
                    },
                    textStyle = TextStyle(fontSize = 1.sp, color = Color.Transparent),
                    cursorBrush = SolidColor(Color.Transparent),
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .focusRequester(keyFocus)
                            // 软键盘退格键：TextField 为空时 onValueChange 无变化
                            // 不触发（Mac 输入框删不动——用户反馈）。IME 的
                            // KEYCODE_DEL 走 KeyEvent 通道，捕获直接转发
                            .onPreviewKeyEvent { event: KeyEvent ->
                                if (event.type == KeyEventType.KeyDown && event.key == Key.Backspace) {
                                    keySenderLocal?.invoke(ScreenControlPacket.KeyCode.BACKSPACE, 0, "")
                                    true
                                } else {
                                    false
                                }
                            },
                )
            }
            LaunchedEffect(keyboardOpen) {
                if (keyboardOpen) {
                    // 焦点变更与输入连接建立是异步的，直接 requestFocus 常被忽略
                    // （终端页同款处理）：延迟一小拍再显式拉起键盘
                    keyFocus.requestFocus()
                    delay(150)
                    keyboardController?.show()
                    // 等键盘完全展开后记录高度：Spacer 用固定值，
                    // 系统键盘收起动画期间不渐变（视频不随动）
                    delay(400)
                    imeHeightFixed = imeInsets.getBottom(density)
                } else {
                    imeHeightFixed = 0
                    keyboardController?.hide()
                }
            }
        }
        // 键盘高度让位（adjustNothing 下 imePadding 不生效）：键盘弹起时
        // 占位把工具栏顶到键盘上方——工具栏顶部 = 画面底部（不覆盖画面）。
        // 用固定高度（键盘完全展开时记录）：收起动画期间画面区不渐变，
        // 视频保持原位，动画结束直接回居中（用户反馈：先下来再上去）
        if (keyboardOpen && imeHeightFixed > 0) {
            Spacer(Modifier.fillMaxWidth().height(with(LocalDensity.current) { imeHeightFixed.toDp() }))
        }
    }
}

/**
 * 远程键盘工具栏：一行可横向滑动。
 * ⌘/⌃/⌥/⇧ 粘性修饰（点按激活高亮，随下一个键发出；再点取消），
 * ⌘Q/⌘C/⌘V 预设组合，特殊键带当前粘性修饰。
 */
@Composable
private fun RemoteKeyBar(
    activeMods: Int,
    capsOn: Boolean,
    onKeyboardToggle: () -> Unit,
    onModToggle: (Int) -> Unit,
    onCapsToggle: () -> Unit,
    onKey: (keyCode: Int, mods: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalAppStrings.current
    Row(
        modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.9f))
            .padding(horizontal = 6.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 可横向滑动的键区（weight 1f 占满，滚动只作用于键区）
        Row(
            Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RemoteKey("⌘", activeMods and ScreenControlPacket.MOD_COMMAND != 0) {
                onModToggle(ScreenControlPacket.MOD_COMMAND)
            }
            RemoteKey("⌃", activeMods and ScreenControlPacket.MOD_CONTROL != 0) {
                onModToggle(ScreenControlPacket.MOD_CONTROL)
            }
            RemoteKey("⌥", activeMods and ScreenControlPacket.MOD_OPTION != 0) {
                onModToggle(ScreenControlPacket.MOD_OPTION)
            }
            RemoteKey("⇧", activeMods and ScreenControlPacket.MOD_SHIFT != 0) {
                onModToggle(ScreenControlPacket.MOD_SHIFT)
            }
            RemoteKey("⇪", capsOn, onClick = onCapsToggle)
            RemoteKey("⌘Q", false) { onKey(ScreenControlPacket.KeyCode.Q, ScreenControlPacket.MOD_COMMAND) }
            RemoteKey("⌘C", false) { onKey(ScreenControlPacket.KeyCode.C, ScreenControlPacket.MOD_COMMAND) }
            RemoteKey("⌘V", false) { onKey(ScreenControlPacket.KeyCode.V, ScreenControlPacket.MOD_COMMAND) }
            RemoteKey("tab", false) { onKey(ScreenControlPacket.KeyCode.TAB, activeMods) }
            RemoteKey("esc", false) { onKey(ScreenControlPacket.KeyCode.ESC, activeMods) }
            RemoteKey("⏎", false) { onKey(ScreenControlPacket.KeyCode.RETURN, activeMods) }
            RemoteKey("space", false) { onKey(ScreenControlPacket.KeyCode.SPACE, activeMods) }
            RemoteKey("⌫", false) { onKey(ScreenControlPacket.KeyCode.BACKSPACE, activeMods) }
            RemoteKey("↑", false) { onKey(ScreenControlPacket.KeyCode.UP, activeMods) }
            RemoteKey("↓", false) { onKey(ScreenControlPacket.KeyCode.DOWN, activeMods) }
            RemoteKey("←", false) { onKey(ScreenControlPacket.KeyCode.LEFT, activeMods) }
            RemoteKey("→", false) { onKey(ScreenControlPacket.KeyCode.RIGHT, activeMods) }
        }
        // ⌨ 固定按钮（工具栏最右，不随键区滚动）：点击收起键盘
        Box(
            Modifier
                .height(38.dp)
                .widthIn(min = 44.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF2563EB).copy(alpha = 0.85f))
                .clickable(onClick = onKeyboardToggle)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "⌨",
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 17.sp,
            )
        }
    }
}

/** 远程键盘按键（圆角方块；激活态蓝色高亮）。 */
@Composable
private fun RemoteKey(
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .height(38.dp)
            .widthIn(min = 38.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) Color(0xFF2563EB).copy(alpha = 0.85f) else Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = Color.White.copy(alpha = 0.9f),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/** 帧率（30/60/120）与画质档位切换：全屏 header 右上角。 */
@Composable
private fun StreamQualitySwitcher(
    fps: Int,
    quality: Int,
    /** 解码器收到的真实尺寸；优先显示它，避免配置档位与实际推流不一致。 */
    videoDims: Pair<Int, Int>?,
    /** 解码能力帧率上限（0 = 未知，全部显示）；隐藏解码器跑不满的档位。 */
    maxFps: Int,
    deliveredFps: Int,
    bitrateKbps: Int,
    jitterMillis: Int,
    droppedPermille: Int,
    onSelect: (Int, String) -> Unit,
    /** 帧率本地立即更新（异步重建前 UI 先反馈——用户反馈：帧率菜单点了不生效）。 */
    onFpsIndex: (Int) -> Unit,
    onQualityIndex: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalAppStrings.current
    val qualities =
        listOf(
            "960:-2" to s.screen.qualityLow,
            "1280:-2" to s.screen.qualityMid,
            "1920:-2" to s.screen.qualityHigh,
            "native" to s.screen.qualityUltra,
        )
    val fpsOptions = listOf(30, 60, 120).filter { maxFps <= 0 || it <= maxFps }
    var menuOpen by remember { mutableStateOf(false) }

    Box(modifier) {
        // 当前档位胶囊（点击展开菜单）
        val qualityOrActualSize =
            videoDims?.let { (width, height) -> "$width×$height" }
                ?: qualities.getOrElse(quality) { qualities[1] }.second
        Text(
            "$fps fps · $qualityOrActualSize",
            color = Color.White.copy(alpha = 0.9f),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            modifier =
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 8.dp, vertical = 5.dp)
                    .clickable { menuOpen = true },
        )
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            if (deliveredFps > 0 || bitrateKbps > 0) {
                DropdownMenuItem(
                    text = {
                        Text(
                            s.screen.streamDiagnostics(
                                deliveredFps,
                                bitrateKbps,
                                jitterMillis,
                                droppedPermille,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    onClick = {},
                    enabled = false,
                )
                HorizontalDivider()
            }
            // 帧率组
            fpsOptions.forEach { f ->
                DropdownMenuItem(
                    text = {
                        Text(
                            "$f fps",
                            fontWeight = if (f == fps) FontWeight.Bold else null,
                            color = if (f == fps) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    onClick = {
                        menuOpen = false
                        // 本地立即更新 + 远端写配置重建（与画质菜单对称）
                        onFpsIndex(f)
                        onSelect(f, qualities[quality].first)
                    },
                )
            }
            HorizontalDivider()
            // 画质组：超清保留更多远端细节，默认仍为标清以控制带宽与编码压力。
            qualities.forEachIndexed { i, (scale, label) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            label,
                            fontWeight = if (i == quality) FontWeight.Bold else null,
                            color = if (i == quality) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    onClick = {
                        menuOpen = false
                        // 超清优先保证文字与细线：从 120fps 直接切超清时先降到
                        // 60fps。用户仍可随后手动选回 120fps（远端会提高码率）。
                        val selectedFps = fpsForQualitySelection(fps, i)
                        onFpsIndex(selectedFps)
                        onQualityIndex(i)
                        onSelect(selectedFps, scale)
                    },
                )
            }
        }
    }
}

/** 超清首次选择优先清晰度；其它档位及用户后续手动选帧率不受限制。 */
internal fun fpsForQualitySelection(
    currentFps: Int,
    qualityIndex: Int,
): Int = if (qualityIndex == 3 && currentFps > 60) 60 else currentFps

/** 推流服务安装引导卡片：服务缺失时按具体原因（ffmpeg 缺失 / 服务未运行）引导一键安装，
 * 安装中实时日志；失败后保留日志尾巴可重试。 */
@Composable
internal fun ScreenServiceGuide(
    installing: Boolean,
    installLog: String,
    /** 缺失原因：true = 远端缺 ffmpeg；false = 服务未运行（端口无监听）。 */
    ffmpegMissing: Boolean,
    /** relay 版本过旧（引导升级而非首次安装）。 */
    needsUpgrade: Boolean = false,
    /** Linux 缺 ffmpeg 且 sudo 需要交互密码。 */
    needsSudoPassword: Boolean = false,
    onInstall: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalAppStrings.current
    Box(
        modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // 图标：主题色浅底圆角容器，视觉更精致
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Monitor,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    s.screen.serviceTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                // 具体原因 + 动作说明（替代通用长文案，信息更准）
                Text(
                    when {
                        needsUpgrade -> s.screen.serviceUpgradeHint
                        ffmpegMissing -> s.screen.serviceHintFfmpeg
                        else -> s.screen.serviceHintNotRunning
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                if (installing) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(
                            s.screen.installingService,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // 安装实时日志：显示最后 8 行，挂住时可见无新进展
                    if (installLog.isNotBlank()) {
                        Text(
                            installLog.lines().takeLast(8).joinToString("\n"),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = monospaceFontFamily(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 8,
                            overflow = TextOverflow.Ellipsis,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(10.dp),
                        )
                    }
                } else {
                    val sudoPassword = remember { mutableStateOf("") }
                    if (needsSudoPassword) {
                        OutlinedTextField(
                            value = sudoPassword.value,
                            onValueChange = { sudoPassword.value = it },
                            label = { Text(s.screen.serviceSudoPasswordLabel) },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            s.screen.serviceSudoPasswordHint,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Button(
                        onClick = {
                            onInstall(if (needsSudoPassword) sudoPassword.value else null)
                        },
                        enabled = !needsSudoPassword || sudoPassword.value.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(s.screen.installService)
                    }
                    // 上次安装失败：错误提示 + 日志尾巴（可重试，按钮仍在）
                    if (installLog.isNotBlank()) {
                        Text(
                            s.screen.installFailed,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            installLog.lines().takeLast(6).joinToString("\n"),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = monospaceFontFamily(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(10.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 全屏画面最大放大倍数（双指缩放上限）。 */
private const val MAX_SCREEN_ZOOM = 4f

/** 隐藏输入框哨兵字符（零宽空格）：保证退格键永远有内容可删。 */
private const val KEY_SENTINEL = "\u200B"

/** 小窗默认尺寸（dp）。 */
const val PIP_DEFAULT_W = 160f
const val PIP_DEFAULT_H = 100f

/** 缩放范围（dp）。 */
private const val PIP_MIN_W = 120f
private const val PIP_MIN_H = 75f
private const val PIP_MAX_W = 360f
private const val PIP_MAX_H = 240f

/**
 * 终端页小窗（画中画）：**可拖动 + 右下角拖拽缩放 + ✕ 关闭**；点击切全屏。
 * [drag]/[pipW]/[pipH] 由调用方持有（remember 于会话 key 块内）：全屏展开/收起时
 * 本组件销毁重建也不会重置位置/尺寸（rememberSaveable 只在 Activity 重建时恢复，
 * 普通的离开组合不保存——v1.4.0 回归：全屏返回后小窗回到右上角默认大小）。
 */
@Composable
fun ScreenPiP(
    state: ScreenUiState,
    onClick: () -> Unit,
    onClose: () -> Unit,
    /** 拖动偏移（会话内保持）。 */
    drag: MutableState<Offset>,
    /** 窗口宽度（dp，会话内保持）。 */
    pipW: MutableState<Float>,
    /** 窗口高度（dp，会话内保持）。 */
    pipH: MutableState<Float>,
    modifier: Modifier = Modifier,
    /** 画布尺寸（钳制边界，px）。 */
    canvasSize: IntSize = IntSize.Zero,
) {
    var ownSize by remember { mutableStateOf(IntSize.Zero) }
    // 钳制边界读最新值但不重启手势：缩放导致尺寸变化时，若 pointerInput 以
    // ownSize 为 key 会重启并中断正在进行的拖动手势（v1.4.0 回归：缩放拖不动）
    val ownSizeState = rememberUpdatedState(ownSize)
    val player = state.player
    val s = LocalAppStrings.current

    Box(
        modifier
            .offset { IntOffset(drag.value.x.roundToInt(), drag.value.y.roundToInt()) }
            .size(pipW.value.dp, pipH.value.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black)
            .onSizeChanged { ownSize = it }
            // 手势统一处理：单指拖动移动（起点在右下角把手区则缩放尺寸）；
            // 双指捏合/张开缩放尺寸（v1.4.0 回归：把手拖不动 + 水平缩放被系统返回抢走）。
            // 不用 detectDragGestures：把手独立手势会被父节点事件先发抢走；
            // 双指缩放需要自定义手势（transform 无法区分把手起点）。
            .pointerInput(canvasSize) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val sz0 = ownSizeState.value
                    val handlePx = 26.dp.toPx()
                    val p = down.position
                    // ✕ / 全屏角标区域（左上/右上角）交给各自 clickable，不参与手势
                    val iconPx = 24.dp.toPx()
                    if ((p.x < iconPx && p.y < iconPx) || (p.x > sz0.width - iconPx && p.y < iconPx)) {
                        return@awaitEachGesture
                    }
                    // 单指起点落在右下角把手区（26dp）→ 单指拖动为缩放
                    var resizing = p.x >= sz0.width - handlePx && p.y >= sz0.height - handlePx
                    var moved = false
                    var multiTouch = false
                    var total = Offset.Zero
                    var prevDist = -1f
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) {
                            // 全部抬起：无移动且未多指 → 点击全屏。替代视频面 clickable：
                            // clickable 先于本手势收到事件，双指捏合位移小时会误判点击（v1.4.0）
                            if (!moved && !multiTouch) onClick()
                            break
                        }
                        if (pressed.size >= 2) {
                            multiTouch = true
                            // 双指：捏合/张开缩放。以两指质心为锚（质心绝对位置不动，
                            // 窗口朝手指方向展开——用户反馈中心锚定「展开方向奇怪」）
                            val p1 = pressed[0].position
                            val p2 = pressed[1].position
                            val dist = (p2 - p1).getDistance()
                            if (prevDist > 0f && dist > 0f) {
                                val zoom = dist / prevDist
                                val oldW = pipW.value
                                val oldH = pipH.value
                                val oldWpx = oldW * density
                                val oldHpx = oldH * density
                                // 质心在小窗内的相对位置（缩放前后保持 → 质心处内容不动）
                                val centroid = (p1 + p2) / 2f
                                val rx = (centroid.x / oldWpx).coerceIn(0f, 1f)
                                val ry = (centroid.y / oldHpx).coerceIn(0f, 1f)
                                pipW.value = (oldW * zoom).coerceIn(PIP_MIN_W, PIP_MAX_W)
                                pipH.value = (oldH * zoom).coerceIn(PIP_MIN_H, PIP_MAX_H)
                                val newWpx = pipW.value * density
                                val newHpx = pipH.value * density
                                // 质心锚定：左上角 = 质心绝对位置 - 相对位置×新尺寸
                                drag.value =
                                    Offset(
                                        drag.value.x + centroid.x - rx * newWpx,
                                        drag.value.y + centroid.y - ry * newHpx,
                                    )
                                // 缩放后钳制在画布内（防拖出屏幕）。
                                // ⚠️ 保护条件须留 margin 余量：maxDown ∈ (0, 4) 时
                                // coerceIn(0, maxDown-4) 会 min>max 抛异常崩溃（用户反馈）
                                if (canvasSize.width > 0) {
                                    val maxLeft = (canvasSize.width - newWpx).toFloat()
                                    val maxDown = (canvasSize.height - newHpx).toFloat()
                                    if (maxLeft > 4f && maxDown > 4f) {
                                        drag.value =
                                            Offset(
                                                drag.value.x.coerceIn(-maxLeft + 4f, 0f),
                                                drag.value.y.coerceIn(0f, maxDown - 4f),
                                            )
                                    }
                                }
                                event.changes.forEach { it.consume() }
                            }
                            prevDist = dist
                        } else {
                            val change = pressed[0]
                            val delta = change.position - change.previousPosition
                            total += delta
                            if (!moved && total.x * total.x + total.y * total.y > slop * slop) moved = true
                            if (moved) {
                                if (resizing) {
                                    // 单指把手：delta 是 px、pip 尺寸是 dp，除以 density 换算
                                    val oldW = pipW.value
                                    val oldH = pipH.value
                                    pipW.value = (oldW + delta.x / density).coerceIn(PIP_MIN_W, PIP_MAX_W)
                                    pipH.value = (oldH + delta.y / density).coerceIn(PIP_MIN_H, PIP_MAX_H)
                                    // 中心锚定：与双指缩放一致（中心不动）
                                    drag.value =
                                        Offset(
                                            drag.value.x - (pipW.value - oldW) / 2f * density,
                                            drag.value.y - (pipH.value - oldH) / 2f * density,
                                        )
                                } else {
                                    // 移动：钳制在画布内（初始右上角，可全画布移动）
                                    val sz = ownSizeState.value
                                    if (canvasSize.width > 0 && sz.width > 0) {
                                        val margin = 4f
                                        // 初始位置 = 画布右上角（调用方 TopEnd + padding）：
                                        // 向左最多到左边缘、向下最多到画布底；向右/向上不可（已贴边）
                                        val maxLeft = (canvasSize.width - sz.width).toFloat()
                                        val maxDown = (canvasSize.height - sz.height).toFloat()
                                        if (maxLeft > margin && maxDown > margin) {
                                            drag.value =
                                                Offset(
                                                    (drag.value.x + delta.x).coerceIn(-maxLeft + margin, 0f),
                                                    (drag.value.y + delta.y).coerceIn(0f, maxDown - margin),
                                                )
                                        }
                                    }
                                }
                                change.consume()
                            }
                        }
                    }
                }
            },
    ) {
        if (player != null) {
            // 无 clickable：点击=全屏由父手势统一判定（clickable 先于手势收到事件，
            // 双指捏合位移小时会误判点击全屏——v1.4.0 回归）
            ScreenVideoSurface(player, Modifier.fillMaxSize())
        } else if (!state.serviceMissing && state.error == null) {
            // 加载中（连接中/等首帧）：纯展示，点击进全屏看详情
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "…",
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                )
            }
        }
        // 服务缺失：小窗中央提示（纯展示不操作；点击进全屏看完整安装引导/按钮）。
        // 按具体原因给文案（ffmpeg 缺失 / relay 旧版 / 服务未运行），安装中显示进度
        if (state.serviceMissing) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(horizontal = 6.dp),
                ) {
                    Icon(
                        Icons.Filled.Monitor,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.75f),
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        when {
                            state.relayNeedsUpgrade -> s.screen.serviceUpgradeHint
                            state.ffmpegMissing -> s.screen.serviceHintFfmpeg
                            else -> s.screen.pipNeedInstall
                        },
                        color = Color.White.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                    if (state.installing) {
                        Text(
                            s.screen.installingService,
                            color = Color.White.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        } else {
            // 错误提示（连接失败/解码失败/首帧超时等）：纯展示，点击进全屏重连
            state.error?.let { msg ->
                Box(
                    Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Icon(
                            Icons.Filled.ErrorOutline,
                            contentDescription = null,
                            tint = Color(0xFFFF6B6B),
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            msg,
                            color = Color.White.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
        // 屏幕状态提示（息屏/锁屏/网络不稳，可恢复）：小窗顶部小条。
        // 与 ✕/全屏角标错开（左右 padding 32dp），画面到达自动清除
        state.screenHint?.let { hint ->
            Text(
                hint,
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 4.dp, start = 32.dp, end = 32.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }

        // 关闭按钮（左上角）：✕ 销毁屏幕会话。圆角半透明胶囊底 + 居中图标，
        // 与全屏页返回按钮同风格（黑 0.55 / 圆角 8dp / 白 0.9）
        Box(
            Modifier
                .align(Alignment.TopStart)
                .padding(5.dp)
                .size(26.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = s.settingsClose,
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(16.dp),
            )
        }

        // 全屏角标（右上角）：点击 = 全屏（同点画面），与关闭按钮同款胶囊底
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(5.dp)
                .size(26.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Fullscreen,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(16.dp),
            )
        }

        // 右下角缩放把手（视觉）：拖拽调整窗口大小由小窗 Box 的手势统一处理——
        // 起点落在此 26dp 区域即缩放（独立手势会被父节点事件先发抢走）。
        // 排除系统返回手势：把手贴屏幕右边缘时水平缩放会被边缘返回抢走
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .size(26.dp)
                .excludeSystemBackGesture(),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val p =
                    Path().apply {
                        moveTo(size.width, size.height)
                        lineTo(size.width - 13f, size.height)
                        lineTo(size.width, size.height - 13f)
                        close()
                    }
                drawPath(p, Color.White.copy(alpha = 0.55f))
            }
        }
    }
}

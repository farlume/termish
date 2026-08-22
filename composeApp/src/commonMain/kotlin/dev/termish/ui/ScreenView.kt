package dev.termish.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.termish.data.Host
import dev.termish.screen.ScreenPlayer
import dev.termish.screen.ScreenSession
import dev.termish.screen.ScreenUiState
import dev.termish.screen.ScreenVideoSurface
import dev.termish.ui.theme.StatusColors
import dev.termish.util.monospaceFontFamily
import kotlin.math.roundToInt

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
    onInstallService: () -> Unit = {},
    /** 就地全屏模式：左上角返回按钮显示「收起」（不跳 tab）；否则显示「返回」。 */
    onClose: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val s = LocalAppStrings.current
    Box(modifier.fillMaxSize().background(Color.Black)) {
        // 画面帧（播放器渲染面，Fit 缩放）
        state.player?.let { p ->
            ScreenVideoSurface(p, Modifier.fillMaxSize())
        }

        // 帧率 / 分辨率角标（右上角小字）
        if (state.connected && (state.fps > 0 || state.frameSize.isNotBlank())) {
            Row(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.frameSize.isNotBlank()) {
                    Text(
                        state.frameSize,
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                if (state.fps > 0) {
                    Text(
                        "${state.fps} fps",
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
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

        // 服务缺失：引导一键安装（herdr 同款卡片：图标 + 说明 + 安装按钮 + 实时日志）
        if (state.serviceMissing) {
            ScreenServiceGuide(
                installing = state.installing,
                installLog = state.installLog,
                onInstall = onInstallService,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // 错误态：居中提示 + 重连/返回
        state.error?.let { msg ->
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        msg,
                        color = StatusColors.Error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (state.ffmpegMissing) {
                        Text(
                            s.screen.ffmpegHint,
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(onClick = onReconnect) {
                        Text(s.screen.reconnect, color = Color.White)
                    }
                    TextButton(onClick = onBack) {
                        Text(s.terminalCancel, color = Color.White.copy(alpha = 0.6f))
                    }
                }
            }
        }

        // 全屏顶部返回按钮：就地全屏（小窗展开）= 收起回终端；屏幕 tab = 返回上一 tab。
        // ⚠️ 必须最后声明（最顶层）：视频面/错误态都是 fillMaxSize 覆盖层，
        // 先声明会被盖住导致全屏无法返回（v1.4.0 回归：看不到也点不到）。
        val backLabel = if (onClose != null) s.screen.collapse else s.screen.back
        Box(
            Modifier
                .align(Alignment.TopStart)
                .padding(10.dp)
                .zIndex(100f)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(onClick = { (onClose ?: onBack)() })
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    backLabel,
                    color = Color.White.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/** 推流服务安装引导卡片：远端服务缺失时引导一键安装 + 实时安装日志（herdr 同款）。 */
@Composable
private fun ScreenServiceGuide(
    installing: Boolean,
    installLog: String,
    onInstall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalAppStrings.current
    Box(
        modifier
            .fillMaxSize()
            .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 24.dp),
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
                    Icons.Filled.Monitor,
                    contentDescription = null,
                    modifier = Modifier.size(44.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    s.screen.serviceTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Text(
                    s.screen.serviceHint,
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(10.dp),
                        )
                    }
                } else {
                    Button(onClick = onInstall) {
                        Text(s.screen.installService)
                    }
                }
            }
        }
    }
}

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
                            // 双指：捏合/张开缩放（以尺寸左上角为锚点，不移动小窗）
                            val p1 = pressed[0].position
                            val p2 = pressed[1].position
                            val dist = (p2 - p1).getDistance()
                            if (prevDist > 0f && dist > 0f) {
                                val zoom = dist / prevDist
                                pipW.value = (pipW.value * zoom).coerceIn(PIP_MIN_W, PIP_MAX_W)
                                pipH.value = (pipH.value * zoom).coerceIn(PIP_MIN_H, PIP_MAX_H)
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
                                    pipW.value = (pipW.value + delta.x / density).coerceIn(PIP_MIN_W, PIP_MAX_W)
                                    pipH.value = (pipH.value + delta.y / density).coerceIn(PIP_MIN_H, PIP_MAX_H)
                                } else {
                                    // 移动：钳制在画布内（初始右上角，可全画布移动）
                                    val sz = ownSizeState.value
                                    if (canvasSize.width > 0 && sz.width > 0) {
                                        val margin = 4f
                                        // 初始位置 = 画布右上角（调用方 TopEnd + padding）：
                                        // 向左最多到左边缘、向下最多到画布底；向右/向上不可（已贴边）
                                        val maxLeft = (canvasSize.width - sz.width).toFloat()
                                        val maxDown = (canvasSize.height - sz.height).toFloat()
                                        if (maxLeft > 0f && maxDown > 0f) {
                                            drag.value = Offset(
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
        } else {
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

        // 关闭按钮（左上角）：✕ 销毁屏幕会话
        Icon(
            Icons.Filled.Close,
            contentDescription = "close screen",
            tint = Color.White.copy(alpha = 0.85f),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(5.dp)
                .size(14.dp)
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(onClick = onClose),
        )

        // 全屏角标（右上角）：点击 = 全屏（同点画面）
        Icon(
            Icons.Filled.Fullscreen,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.55f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(5.dp)
                .size(14.dp)
                .clickable(onClick = onClick),
        )

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
                val p = Path().apply {
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

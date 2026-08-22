package dev.termish.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
    /** 就地全屏模式：右上角显示 ✕ 关闭按钮（不跳 tab）。 */
    onClose: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val s = LocalAppStrings.current
    Box(modifier.fillMaxSize().background(Color.Black)) {
        // 就地全屏：右上角 ✕ 收起回终端
        if (onClose != null) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable(onClick = onClose)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    "✕ 收起",
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
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
private const val PIP_DEFAULT_W = 160f
private const val PIP_DEFAULT_H = 100f
/** 缩放范围（dp）。 */
private const val PIP_MIN_W = 120f
private const val PIP_MIN_H = 75f
private const val PIP_MAX_W = 360f
private const val PIP_MAX_H = 240f

/**
 * 终端页小窗（画中画）：**可拖动 + 右下角拖拽缩放 + ✕ 关闭**；点击切到屏幕 tab 全屏。
 * 拖动偏移与尺寸在会话内保持（remember 于 key(controller.sessionId) 块内）。
 */
@Composable
fun ScreenPiP(
    state: ScreenUiState,
    onClick: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** 画布尺寸（钳制边界，px）。 */
    canvasSize: IntSize = IntSize.Zero,
) {
    var drag by remember { mutableStateOf(Offset.Zero) }
    var pipW by remember { mutableFloatStateOf(PIP_DEFAULT_W) }
    var pipH by remember { mutableFloatStateOf(PIP_DEFAULT_H) }
    var ownSize by remember { mutableStateOf(IntSize.Zero) }
    val player = state.player

    Box(
        modifier
            .offset { IntOffset(drag.x.roundToInt(), drag.y.roundToInt()) }
            .size(pipW.dp, pipH.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black)
            .onSizeChanged { ownSize = it }
            // 拖动：钳制在画布内（初始右上角，可全画布移动）
            .pointerInput(canvasSize, ownSize) {
                detectDragGestures { change, delta ->
                    change.consume()
                    if (canvasSize.width <= 0 || ownSize.width <= 0) return@detectDragGestures
                    val margin = 4f
                    val maxX = (canvasSize.width - ownSize.width).toFloat()
                    val maxY = (canvasSize.height - ownSize.height).toFloat()
                    drag = Offset(
                        (drag.x + delta.x).coerceIn(-maxX + margin, maxX - margin),
                        (drag.y + delta.y).coerceIn(-maxY + margin, maxY - margin),
                    )
                }
            },
    ) {
        if (player != null) {
            ScreenVideoSurface(
                player,
                Modifier.fillMaxSize().clickable(onClick = onClick),
            )
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

        // 右下角缩放把手：拖拽调整窗口大小
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .size(26.dp)
                .pointerInput(ownSize) {
                    detectDragGestures { change, delta ->
                        change.consume()
                        pipW = (pipW + delta.x).coerceIn(PIP_MIN_W, PIP_MAX_W)
                        pipH = (pipH + delta.y).coerceIn(PIP_MIN_H, PIP_MAX_H)
                    }
                },
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

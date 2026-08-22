package dev.termish.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.termish.ui.theme.TerminalTheme
import dev.termish.util.monospaceFontFamily
import kotlin.math.PI
import kotlin.math.sin

/** 语音输入 FAB 的 UI 状态（由调用方驱动）。 */
enum class VoiceUiState { IDLE, LISTENING, RECOGNIZING }

/** 波浪条数量。 */
private const val WAVE_BARS = 13

/** 各条静态权重（中间高、两边低，弧形轮廓）。 */
private val WAVE_WEIGHTS =
    FloatArray(WAVE_BARS) { i ->
        val x = (i - (WAVE_BARS - 1) / 2f) / ((WAVE_BARS - 1) / 2f)
        (0.35f + 0.65f * (1f - x * x)).toFloat() // 二次曲线：中间 1.0，两端 0.35
    }

/** 每根条独立的流动相位（错开 → 波浪横向流动感）。 */
private val WAVE_PHASES = FloatArray(WAVE_BARS) { i -> i * 0.55f }

/** 每根条独立的波动频率（高低错落，模拟真实声波）。 */
private val WAVE_FREQS = FloatArray(WAVE_BARS) { i -> 2.2f + (i % 3) * 0.45f }

/**
 * 重置小角标：语音按钮被拖动后出现在右上角，点击把按钮重置回屏幕水平中央。
 * 小巧（26dp）、不喧宾夺主；未拖动时不显示。
 */
@Composable
fun VoiceResetBadge(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(26.dp)
            .shadow(4.dp, CircleShape)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Refresh,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(15.dp),
        )
    }
}

/**
 * 屏幕正中（水平）的语音开始按钮：大号品牌绿圆钮 + 麦克风（点一下直接开始，
 * 免去右下角菜单两步）。外层负责拖动，**长按 = 重置回默认位置**。
 * 点击后原地切换为录音态组件（红按钮 + 浮层）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VoiceStartButton(
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(64.dp)
            .shadow(10.dp, CircleShape)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Mic,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(30.dp),
        )
    }
}

/** 录音态中间大按钮（水平居中、大于菜单 FAB）：
 * - LISTENING：72dp 红色圆钮 + 白色麦克风（点击 = 结束并发送）
 * - RECOGNIZING：转圈
 * 与浮层（转写 + 声波）同列居中排布在工具栏上方。
 */
@Composable
fun BigVoiceStopButton(
    state: VoiceUiState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val recognizing = state == VoiceUiState.RECOGNIZING
    Box(
        modifier
            .size(72.dp)
            .shadow(8.dp, CircleShape)
            .clip(CircleShape)
            .background(
                if (recognizing) {
                    MaterialTheme.colorScheme.error.copy(
                        alpha = 0.55f,
                    )
                } else {
                    MaterialTheme.colorScheme.error
                },
            ).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (recognizing) {
            CircularProgressIndicator(
                modifier = Modifier.size(30.dp),
                strokeWidth = 3.dp,
                color = MaterialTheme.colorScheme.onError,
            )
        } else {
            Icon(
                Icons.Filled.Mic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onError,
                modifier = Modifier.size(34.dp),
            )
        }
    }
}

/** 录音态底部浮层（微信/讯飞式）：不遮挡终端中部内容。 */
@Composable
fun VoiceRecordingOverlayHost(
    visible: Boolean,
    state: VoiceUiState,
    /** 实时音量 0..1（录音回调每 ~200ms 更新，动画层平滑）。 */
    level: Float,
    /** 实时转写文本（中间结果，随包替换更新）。 */
    partialText: String,
    /** 录音已进行秒数。 */
    seconds: Int,
    theme: TerminalTheme,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it / 2 }, animationSpec = tween(220)) + fadeIn(tween(220)),
        exit = slideOutVertically(targetOffsetY = { it / 2 }, animationSpec = tween(180)) + fadeOut(tween(180)),
        modifier = modifier,
    ) {
        VoiceRecordingOverlay(state, level, partialText, seconds, theme)
    }
}

@Composable
private fun VoiceRecordingOverlay(
    state: VoiceUiState,
    level: Float,
    partialText: String,
    seconds: Int,
    theme: TerminalTheme,
) {
    val s = LocalAppStrings.current
    val recognizing = state == VoiceUiState.RECOGNIZING
    val surface = MaterialTheme.colorScheme.surface
    val onSurface = MaterialTheme.colorScheme.onSurface
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(surface.copy(alpha = 0.97f))
            .padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 实时转写：边说边出字（等宽，最多 2 行）
        Text(
            partialText.ifBlank { if (recognizing) s.voice.recognizing else s.voice.listening },
            color = if (partialText.isBlank()) muted else onSurface,
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = monospaceFontFamily(),
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 480.dp),
        )

        // 流动声波：Canvas 单时间驱动，每根条独立相位/频率 → 横向流动感
        if (!recognizing) {
            FlowingWave(level = level, accent = accent, modifier = Modifier.height(36.dp))
        }

        // 状态行：录音计时 / 识别提示
        Text(
            if (recognizing) s.voice.recognizing else s.voice.recordingSeconds(seconds),
            color = muted,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/**
 * 流动声波：音量调制整体振幅，每根条叠加独立相位/频率的正弦 → 波浪横向流动。
 * 单一 infinite transition 驱动（不每根条各开一个动画）。
 */
@Composable
private fun FlowingWave(
    level: Float,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "wave")
    val time by transition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart),
        label = "waveTime",
    )
    Canvas(modifier) {
        val barW = 3.dp.toPx()
        val gap = 5.dp.toPx()
        val totalW = barW * WAVE_BARS + gap * (WAVE_BARS - 1)
        var x = (size.width - totalW) / 2f
        val maxH = size.height
        for (i in 0 until WAVE_BARS) {
            // 音量振幅 + 独立正弦波动（相位错开 → 流动感）
            val wobble = (sin(time * WAVE_FREQS[i] + WAVE_PHASES[i]) * 0.5f + 0.5f).toFloat()
            val h = maxH * (0.16f + (level * WAVE_WEIGHTS[i] * 0.7f + wobble * 0.14f).coerceIn(0f, 1f))
            drawRoundRect(
                color = accent,
                topLeft = Offset(x, (maxH - h) / 2f),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2f, barW / 2f),
            )
            x += barW + gap
        }
    }
}

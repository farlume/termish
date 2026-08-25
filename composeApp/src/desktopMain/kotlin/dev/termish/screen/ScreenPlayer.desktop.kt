package dev.termish.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/** 桌面占位：屏幕推流播放未实现（开发 harness 场景，非核心）。 */
actual class ScreenPlayer actual constructor(
    private val onReady: () -> Unit,
    private val onError: (ScreenPlayerFailure) -> Unit,
) {
    actual fun start() {
    }

    actual fun feed(data: ByteArray) {
    }

    actual fun stop() {
    }

    actual val videoDims: MutableState<Pair<Int, Int>?> = mutableStateOf(null)

    actual val lastRenderedAtMillis: Long = 0L

    actual val renderSurfaceAttached: Boolean = false
}

@Composable
actual fun ScreenVideoSurface(
    player: ScreenPlayer?,
    modifier: Modifier,
    alignBottom: Boolean,
) {
    Box(modifier.background(Color.Black))
}

/** 桌面无屏幕推流（stub）：返回 0 = 未知，不钳制。 */
actual fun probeDecoderMaxFps(
    width: Int,
    height: Int,
): Int = 0

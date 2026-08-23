package dev.termish.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/** iOS 占位：SSH/TCP 传输已实现，视频硬解与渲染待接 VideoToolbox。 */
actual class ScreenPlayer actual constructor(
    private val onReady: () -> Unit,
    private val onError: (ScreenPlayerFailure) -> Unit,
) {
    actual fun start() {
        onError(ScreenPlayerFailure.Unsupported)
    }

    actual fun feed(data: ByteArray) {
    }

    actual fun stop() {
    }

    actual val videoDims: MutableState<Pair<Int, Int>?> = mutableStateOf(null)
}

@Composable
actual fun ScreenVideoSurface(
    player: ScreenPlayer?,
    modifier: Modifier,
    alignBottom: Boolean,
) {
    Box(modifier.background(Color.Black))
}

/** iOS 屏幕推流未实现（stub）：返回 0 = 未知，不钳制。 */
actual fun probeDecoderMaxFps(
    width: Int,
    height: Int,
): Int = 0

package dev.termish.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/** iOS 占位：屏幕推流播放未实现（传输层 libssh2 也缺 exec raw 通道）。 */
actual class ScreenPlayer actual constructor(
    private val onReady: () -> Unit,
    private val onError: (String) -> Unit,
) {
    actual fun start() {
        onError("iOS 暂不支持远程画面")
    }

    actual fun feed(data: ByteArray) {
    }

    actual fun stop() {
    }
}

@Composable
actual fun ScreenVideoSurface(player: ScreenPlayer?, modifier: Modifier) {
    Box(modifier.background(Color.Black))
}

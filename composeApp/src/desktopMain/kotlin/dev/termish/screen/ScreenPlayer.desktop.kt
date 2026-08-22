package dev.termish.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/** 桌面占位：屏幕推流播放未实现（开发 harness 场景，非核心）。 */
actual class ScreenPlayer actual constructor(
    private val onReady: () -> Unit,
    private val onError: (String) -> Unit,
) {
    actual fun start() {
    }

    actual fun feed(data: ByteArray) {
    }

    actual fun stop() {
    }
}

@Composable
actual fun ScreenVideoSurface(
    player: ScreenPlayer?,
    modifier: Modifier,
) {
    Box(modifier.background(Color.Black))
}

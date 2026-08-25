package dev.termish.ui

import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import dev.termish.AppContext

@Composable
@Suppress("DEPRECATION") // adjustResize 仍是这里跨 OEM 可恢复输入区高度的兼容路径。
actual fun AgentImeResizeEffect() {
    val window = AppContext.currentActivity?.window
    DisposableEffect(window) {
        val previousMode = window?.attributes?.softInputMode
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        onDispose {
            previousMode?.let(window::setSoftInputMode)
        }
    }
}

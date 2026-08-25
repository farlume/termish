package dev.termish.ui

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

@Composable
actual fun PlatformStatusBarIcons(lightIcons: Boolean) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            // 浅色背景 → 深色图标（isAppearanceLightStatusBars=true）；深色背景 → 浅色图标
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !lightIcons
        }
    }
}

/** 视频全屏沉浸式：隐藏/恢复系统状态栏（画面铺满整个屏幕）。 */
@Composable
actual fun PlatformImmersiveMode(immersive: Boolean) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        DisposableEffect(view, immersive) {
            val controller =
                (view.context as? Activity)?.window?.let { window ->
                    WindowCompat.getInsetsController(window, view)
                }
            if (controller != null) {
                if (immersive) {
                    controller.hide(WindowInsetsCompat.Type.statusBars())
                } else {
                    controller.show(WindowInsetsCompat.Type.statusBars())
                }
            }
            onDispose {
                // 页面可能在全屏收起动画结束前直接离开；组合销毁时必须恢复，
                // 否则后续主机页拿到 0 状态栏 inset，Header 会永久变短。
                if (immersive) {
                    controller?.show(WindowInsetsCompat.Type.statusBars())
                }
            }
        }
    }
}

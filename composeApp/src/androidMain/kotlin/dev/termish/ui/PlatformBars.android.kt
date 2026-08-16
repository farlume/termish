package dev.termish.ui

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

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

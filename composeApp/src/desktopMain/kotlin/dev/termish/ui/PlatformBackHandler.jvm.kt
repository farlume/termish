package dev.termish.ui

import androidx.compose.runtime.Composable

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // 桌面端暂无系统返回手势，暂不处理
}

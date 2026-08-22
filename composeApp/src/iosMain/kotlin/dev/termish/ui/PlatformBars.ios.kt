package dev.termish.ui

import androidx.compose.runtime.Composable

@Composable
actual fun PlatformStatusBarIcons(lightIcons: Boolean) {
    // iOS 状态栏样式由 Info.plist/宿主控制，此处无需处理
}

@Composable
actual fun PlatformImmersiveMode(immersive: Boolean) {
    // iOS 全屏时内容铺满由 Compose 层处理（状态栏为灵动岛/胶囊，无法隐藏）
}

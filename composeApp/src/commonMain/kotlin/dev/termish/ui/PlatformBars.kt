package dev.termish.ui

import androidx.compose.runtime.Composable

/**
 * 平台系统栏（状态栏图标）外观：深色背景用浅色图标（lightIcons=true），
 * 浅色背景用深色图标（lightIcons=false）。跟随应用主题实时切换。
 */
@Composable
expect fun PlatformStatusBarIcons(lightIcons: Boolean)

/**
 * 沉浸式模式（视频全屏）：immersive=true 时隐藏系统状态栏，退出时恢复。
 * 非 Android 平台为空实现（内容铺满由 Compose 层处理）。
 */
@Composable
expect fun PlatformImmersiveMode(immersive: Boolean)

package dev.termish.ui

import androidx.compose.runtime.Composable

/**
 * 平台系统栏（状态栏图标）外观：深色背景用浅色图标（lightIcons=true），
 * 浅色背景用深色图标（lightIcons=false）。跟随应用主题实时切换。
 */
@Composable
expect fun PlatformStatusBarIcons(lightIcons: Boolean)

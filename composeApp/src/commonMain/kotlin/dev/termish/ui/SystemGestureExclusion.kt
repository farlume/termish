package dev.termish.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 将该区域排除出系统返回手势（Android 侧边返回/预测性返回）。
 * 用于小窗缩放把手：把手贴屏幕右边缘时，从把手开始的水平缩放手势
 * 会被系统返回手势抢走（拖一下直接回主页，窗口宽度调不了）。
 * 非 Android 平台为空实现。
 */
@Composable
expect fun Modifier.excludeSystemBackGesture(): Modifier

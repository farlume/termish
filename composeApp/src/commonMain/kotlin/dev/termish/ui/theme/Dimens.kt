package dev.termish.ui.theme

import androidx.compose.ui.unit.dp

/**
 * 设计系统尺寸 token：全 App 间距/圆角统一从这里取，不再散落硬编码数值。
 *
 * 间距基于 4dp 网格：xs=4 sm=8 md=12 lg=16 xl=24 xxl=32。
 */
object Spacing {
    val Xs = 4.dp
    val Sm = 8.dp
    val Md = 12.dp
    val Lg = 16.dp
    val Xl = 24.dp
    val Xxl = 32.dp
}

/** 圆角 token。 */
object Corners {
    val Sm = 8.dp
    val Md = 12.dp
    val Lg = 16.dp
    val Full = 999.dp
}

/** 组件尺寸 token。 */
object Sizes {
    /** 紧凑页头内容高度（不含状态栏避让）。 */
    val HeaderCompact = 48.dp

    /** 列表项状态圆点。 */
    val StatusDot = 10.dp

    /** 设置页头像。 */
    val Avatar = 72.dp
}

/** 远程画面悬浮键盘 / 虚拟鼠标尺寸。 */
object ScreenControlDimens {
    val FloatingButton = 44.dp
    val FloatingIcon = 24.dp
    val FloatingMargin = 14.dp
    val FloatingSpacing = 8.dp

    val MousePanelWidth = 128.dp
    val MousePanelHeight = 158.dp
    val MouseTopButtonsHeight = 76.dp
    val MouseScrollWidth = 38.dp
    val MouseScrollHeight = 98.dp
    val MouseScrollTopOffset = (-6).dp
    val MouseCloseButton = 32.dp
    val MouseCloseGap = 6.dp
    val MouseCursorWidth = 22.dp
    val MouseCursorHeight = 26.dp
    val MouseCursorPanelOffsetX = 26.dp
    val MouseCursorPanelOffsetY = 8.dp
    val MousePanelGripHeight = 40.dp
    val MouseScrollStep = 32.dp
    val MouseDivider = 1.dp
    val MouseStroke = 1.dp
    val MouseScrollLine = 3.dp
    val MouseGripDot = 3.dp
}

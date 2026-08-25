package dev.termish.ui.theme

import androidx.compose.ui.unit.dp

/**
 * 设计系统尺寸 token：全 App 间距/圆角统一从这里取，不再散落硬编码数值。
 *
 * 间距基于 4dp 网格：xs=4 sm=8 md=12 lg=16 xl=24 xxl=32。
 */
object Spacing {
    val None = 0.dp
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
    /** 通用细边框。 */
    val BorderThin = 1.dp

    /** 紧凑页头内容高度（不含状态栏避让）。 */
    val HeaderCompact = 48.dp

    /** 列表项状态圆点。 */
    val StatusDot = 10.dp

    /** 设置页头像。 */
    val Avatar = 72.dp

    /** 通用小图标。 */
    val IconSmall = 18.dp

    /** 通用中图标 / 紧凑加载指示器。 */
    val IconMedium = 24.dp

    /** 最小触控目标。 */
    val TouchTarget = 48.dp

    /** Agent 远端目录选择器固定内容高度；列表在内部滚动，确认按钮始终可见。 */
    val AgentDirectorySheet = 400.dp

    /** Agent 工作区侧栏占屏幕宽度的比例，保留主界面以维持抽屉层级感。 */
    val AgentDrawerWidthFraction = 0.7f

    /** Agent 对话头像。 */
    val AgentAvatar = 32.dp

    /** Agent 输入区附件 Chip 最大宽度，超长文件名在内部省略。 */
    val AgentAttachmentChipMaxWidth = 240.dp

    /** Agent 输入框斜杠命令候选区最大高度。 */
    val AgentSlashMenuMaxHeight = 224.dp

    /** Agent 工具时间线轨道宽度与线宽。 */
    val AgentTimelineRailWidth = 20.dp
    val AgentTimelineLineWidth = 2.dp
    val AgentTimelineDot = 8.dp

    /** Agent 时间线条目的统一图标容器。 */
    val AgentActivityIconContainer = 36.dp

    /** 用户消息气泡最多占对话内容区的宽度比例，短消息仍按内容自适应。 */
    val AgentUserBubbleMaxWidthFraction = 0.86f

    /** Agent 回复进行中的三点指示器。 */
    val AgentTypingDot = 6.dp

    /** Agent 对话页从屏幕左缘触发返回手势的范围。 */
    val AgentBackGestureEdge = 32.dp

    /** Agent 对话页右滑返回所需的最小水平位移。 */
    val AgentBackGestureThreshold = 72.dp

    /** 无实体 Header 时，为顶部悬浮导航按钮预留的内容净空。 */
    val AgentChromeClearance = 56.dp
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

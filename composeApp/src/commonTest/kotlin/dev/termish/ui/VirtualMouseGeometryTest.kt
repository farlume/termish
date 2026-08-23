package dev.termish.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VirtualMouseGeometryTest {
    @Test
    fun fittedFrameMatchesCenteredLetterboxAndBottomAlignment() {
        val centered = fittedScreenRect(1080f, 2000f, 1920f, 1080f, 1f, 0f, 0f, false)
        assertEquals(0f, centered.left, 0.01f)
        assertEquals(696.25f, centered.top, 0.01f)
        assertEquals(1080f, centered.right, 0.01f)
        assertEquals(1303.75f, centered.bottom, 0.01f)

        val bottom = fittedScreenRect(1080f, 2000f, 1920f, 1080f, 1f, 0f, 0f, true)
        assertEquals(1392.5f, bottom.top, 0.01f)
        assertEquals(2000f, bottom.bottom, 0.01f)
    }

    @Test
    fun rightControlAreaShiftsWithoutResizingFrame() {
        val normal = fittedScreenRect(1080f, 2000f, 1920f, 1080f, 1f, 0f, 0f, false)
        val shifted = fittedScreenRect(1080f, 2000f, 1920f, 1080f, 1f, -420f, 0f, false)
        assertEquals(normal.width, shifted.width, 0.01f)
        assertEquals(normal.height, shifted.height, 0.01f)
        assertEquals(normal.left - 420f, shifted.left, 0.01f)
        assertEquals(normal.right - 420f, shifted.right, 0.01f)
    }

    @Test
    fun cursorCoordinateComesFromFixedPanelAnchor() {
        val frame = ScreenRect(10f, 20f, 1010f, 520f)
        assertEquals(ScreenPoint(0.25f, 0.5f), cursorAtVirtualMouseAnchor(ScreenPoint(260f, 270f), frame))
        assertEquals(ScreenPoint(1f, 0f), cursorAtVirtualMouseAnchor(ScreenPoint(9999f, -20f), frame))
    }

    @Test
    fun cursorDrawingStaysInsideFrameWhileHotspotReachesRightBottom() {
        val frame = ScreenRect(10f, 20f, 1010f, 520f)
        val hotspot = cursorHotspot(ScreenPoint(1f, 1f), frame)
        assertEquals(ScreenPoint(1010f, 520f), hotspot)
        assertEquals(ScreenPoint(978f, 488f), cursorVisualOrigin(hotspot, frame, 32f, 32f))
    }

    @Test
    fun panelAnchorAndCursorStayInsideRemoteFrame() {
        val frame = ScreenRect(10f, 20f, 1010f, 520f)
        val left = clampVirtualMouseAnchor(ScreenPoint(-999f, -999f), frame, 800f, 200f, 80f)
        assertEquals(ScreenPoint(10f, 80f), left)

        val rightBottom = clampVirtualMouseAnchor(ScreenPoint(9999f, 9999f), frame, 800f, 200f, 80f)
        assertEquals(ScreenPoint(1010f, 520f), rightBottom)
    }

    @Test
    fun dockingStartsOnlyWhenWholeControlReachesRightEdge() {
        assertFalse(shouldDockVirtualMouseRight(300f, 700f, 1080f))
        assertTrue(shouldDockVirtualMouseRight(380f, 700f, 1080f))
    }

    @Test
    fun viewportWidthClampsPanelRightEdgeToViewportEdge() {
        // 视频被右侧控制区推走（frame.left 为负）后，anchor 仍可超出 frame.right
        // 挤入控制区，但面板右边缘（anchor + controlWidth）不超出视口右缘
        val pushedFrame = ScreenRect(-100f, 20f, 900f, 520f)
        val anchor = clampVirtualMouseAnchor(ScreenPoint(9999f, 100f), pushedFrame, 800f, 200f, 80f, 1080f - 700f)
        // maxX = 1080 - 700 = 380
        assertEquals(380f, anchor.x)
        assertEquals(100f, anchor.y)
    }

    @Test
    fun viewportWidthKeepsPanelFullyVisibleWhenPushed() {
        // 完全推开（overlap = controlWidth）时面板右边缘正好贴视口右缘：
        // 视口 1080，anchor + controlWidth(700) = 1080
        val pushedFrame = ScreenRect(-700f, 20f, 300f, 520f)
        val anchor = clampVirtualMouseAnchor(ScreenPoint(9999f, 100f), pushedFrame, 800f, 200f, 80f, 380f)
        assertEquals(380f, anchor.x)
    }

    @Test
    fun pushAccumulatesOvershootInsteadOfDiscarding() {
        // 视口 1080 / 控制区 700 → maxAnchorX=380。rawX 未过右缘：不推、anchor 跟随
        val before = computeVirtualMousePush(300f, 1080f, 700f)
        assertEquals(0f, before.overlapPx, 0.01f)
        assertEquals(300f, before.anchorX, 0.01f)

        // 过冲 200：anchor 钉在 380，视频左移 200（过冲累积而不是每帧丢弃——
        // 否则拖到右缘卡住、光标永远够不到视频最右列）
        val partial = computeVirtualMousePush(580f, 1080f, 700f)
        assertEquals(200f, partial.overlapPx, 0.01f)
        assertEquals(380f, partial.anchorX, 0.01f)

        // 过冲超过控制区宽：满推（overlap=700），视频右缘 = 1080-700 = 380 =
        // anchor ——光标恰好点到视频最右列（ToDesk 效果）；更多过冲不再累积
        val full = computeVirtualMousePush(9999f, 1080f, 700f)
        assertEquals(700f, full.overlapPx, 0.01f)
        assertEquals(380f, full.anchorX, 0.01f)
    }

    @Test
    fun pushSpringsBackBeforePanelMovesLeft() {
        // 满推态左拖：rawX 回落先回弹视频（overlap → 0），面板钉着不动；
        // 回完（rawX ≤ maxAnchorX）面板才跟随左移——与右推天然互逆
        val springBack = computeVirtualMousePush(780f, 1080f, 700f)
        assertEquals(400f, springBack.overlapPx, 0.01f)
        assertEquals(380f, springBack.anchorX, 0.01f)

        val reattached = computeVirtualMousePush(200f, 1080f, 700f)
        assertEquals(0f, reattached.overlapPx, 0.01f)
        assertEquals(200f, reattached.anchorX, 0.01f)
    }

    @Test
    fun pushHandlesNarrowViewport() {
        // 视口比控制区还窄（极端小窗/分屏）：maxAnchorX 钳到 0，不崩不出负值
        val push = computeVirtualMousePush(100f, 500f, 700f)
        assertEquals(100f, push.overlapPx, 0.01f)
        assertEquals(0f, push.anchorX, 0.01f)
    }

    @Test
    fun zoomedPushCapExtendsToBringRightEdgeToCursor() {
        // 放大后画面右缘在视口外（2× 宽）：推量上限变大，鼠标钉在右缘、
        // 画面持续平移过来——鼠标永不滑出屏幕（用户反馈）
        val partial = computeVirtualMousePush(1500f, 1080f, 700f, 2160f)
        assertEquals(1120f, partial.overlapPx, 0.01f)
        assertEquals(380f, partial.anchorX, 0.01f)

        // 满推：右缘 2160 - 1780 = 380 = anchor——光标恰能点到画面最右列
        val full = computeVirtualMousePush(9999f, 1080f, 700f, 2160f)
        assertEquals(1780f, full.overlapPx, 0.01f)
        assertEquals(380f, full.anchorX, 0.01f)
    }

    @Test
    fun zoomedPushCapWhenRightEdgeAlreadyVisible() {
        // 放大但右缘已在视口内（500 < 1080）：只需推 120 就把右缘送到光标处，
        // 不再多推（避免把内容无意义地推走）
        val push = computeVirtualMousePush(9999f, 1080f, 700f, 500f)
        assertEquals(120f, push.overlapPx, 0.01f)
        assertEquals(380f, push.anchorX, 0.01f)
    }
}

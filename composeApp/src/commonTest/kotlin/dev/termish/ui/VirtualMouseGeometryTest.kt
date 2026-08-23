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
}

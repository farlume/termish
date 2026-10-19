package dev.termish.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SvgPreviewSizeTest {
    @Test
    fun viewBoxProvidesMissingDimensions() {
        assertEquals(SvgPreviewSize(400f, 200f, 400, 200), svgPreviewSize(-1f, -1f, 400f, 200f))
        assertEquals(SvgPreviewSize(800f, 400f, 800, 400), svgPreviewSize(800f, 0f, 400f, 200f))
        assertEquals(SvgPreviewSize(100f, 50f, 100, 50), svgPreviewSize(0f, 50f, 400f, 200f))
    }

    @Test
    fun missingOrInvalidDimensionsHaveFiniteDefault() {
        assertEquals(SvgPreviewSize(300f, 150f, 300, 150), svgPreviewSize(Float.NaN, Float.POSITIVE_INFINITY, 0f, -1f))
    }

    @Test
    fun largeCanvasIsBoundedWithoutChangingViewport() {
        assertEquals(SvgPreviewSize(100_000f, 50_000f, 2048, 1024), svgPreviewSize(100_000f, 50_000f, 0f, 0f))
        val thin = svgPreviewSize(Float.MAX_VALUE, 1f, 0f, 0f)
        assertEquals(2048, thin.pixelWidth)
        assertTrue(thin.pixelHeight in 1..2048)
    }
}

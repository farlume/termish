package dev.termish.util

import kotlin.math.max
import kotlin.math.roundToInt

/** 保留文档坐标系，只缩小输出位图，防止超大 SVG 分配无界像素内存。 */
internal data class SvgPreviewSize(
    val viewportWidth: Float,
    val viewportHeight: Float,
    val pixelWidth: Int,
    val pixelHeight: Int,
)

internal fun svgPreviewSize(
    width: Float,
    height: Float,
    viewBoxWidth: Float,
    viewBoxHeight: Float,
): SvgPreviewSize {
    fun Float.valid() = isFinite() && this > 0f
    val hasViewBox = viewBoxWidth.valid() && viewBoxHeight.valid()
    val ratio = if (hasViewBox) viewBoxWidth.toDouble() / viewBoxHeight else 2.0
    val w =
        when {
            width.valid() -> width.toDouble()
            height.valid() && hasViewBox -> height * ratio
            hasViewBox -> viewBoxWidth.toDouble()
            else -> 300.0
        }
    val h =
        when {
            height.valid() -> height.toDouble()
            width.valid() && hasViewBox -> width / ratio
            hasViewBox -> viewBoxHeight.toDouble()
            else -> 150.0
        }
    require(w.isFinite() && h.isFinite() && w <= Float.MAX_VALUE && h <= Float.MAX_VALUE)
    val scale = minOf(1.0, 2048.0 / max(w, h))
    return SvgPreviewSize(w.toFloat(), h.toFloat(), max(1, (w * scale).roundToInt()), max(1, (h * scale).roundToInt()))
}

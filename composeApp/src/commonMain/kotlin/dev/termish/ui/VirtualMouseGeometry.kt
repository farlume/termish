package dev.termish.ui

import kotlin.math.max
import kotlin.math.min

/** 不依赖 Compose 的浮点坐标，供虚拟鼠标边界逻辑与单元测试共用。 */
internal data class ScreenPoint(
    val x: Float,
    val y: Float,
)

/** 远程画面在播放器容器内的实际可见矩形。 */
internal data class ScreenRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
}

/**
 * 计算 Fit 画面的实际矩形；公式须与 [ScreenVideoSurface] 和外层 graphicsLayer
 * 保持一致，虚拟光标才能落在真正的视频像素内而不是播放器黑边内。
 */
internal fun fittedScreenRect(
    containerWidth: Float,
    containerHeight: Float,
    videoWidth: Float,
    videoHeight: Float,
    zoom: Float,
    panX: Float,
    panY: Float,
    alignBottom: Boolean,
): ScreenRect {
    if (containerWidth <= 0f || containerHeight <= 0f) return ScreenRect(0f, 0f, 0f, 0f)
    val sourceWidth = videoWidth.takeIf { it > 0f } ?: containerWidth
    val sourceHeight = videoHeight.takeIf { it > 0f } ?: containerHeight
    val fit = min(containerWidth / sourceWidth, containerHeight / sourceHeight)
    val fittedWidth = sourceWidth * fit
    val fittedHeight = sourceHeight * fit
    val safeZoom = zoom.coerceAtLeast(1f)
    val left = (containerWidth - fittedWidth) / 2f * safeZoom + panX
    val topBase = if (alignBottom) containerHeight - fittedHeight else (containerHeight - fittedHeight) / 2f
    val top = topBase * safeZoom + panY
    return ScreenRect(
        left = left,
        top = top,
        right = left + fittedWidth * safeZoom,
        bottom = top + fittedHeight * safeZoom,
    )
}

/** 固定在虚拟鼠标左上方的箭头热点，换算为发送给远端的 0..1 坐标。 */
internal fun cursorAtVirtualMouseAnchor(
    anchor: ScreenPoint,
    frame: ScreenRect,
): ScreenPoint {
    if (frame.width <= 0f || frame.height <= 0f) return ScreenPoint(0f, 0f)
    return ScreenPoint(
        x = ((anchor.x - frame.left) / frame.width).coerceIn(0f, 1f),
        y = ((anchor.y - frame.top) / frame.height).coerceIn(0f, 1f),
    )
}

/** 归一化远端坐标在本地画面中的热点位置。 */
internal fun cursorHotspot(
    cursor: ScreenPoint,
    frame: ScreenRect,
): ScreenPoint =
    ScreenPoint(
        x = frame.left + cursor.x.coerceIn(0f, 1f) * frame.width,
        y = frame.top + cursor.y.coerceIn(0f, 1f) * frame.height,
    )

/**
 * 箭头热点可到达画面四条边，但箭头图形本身不能越界：只移动绘制原点，
 * 不改变发送给远端的热点坐标。
 */
internal fun cursorVisualOrigin(
    hotspot: ScreenPoint,
    frame: ScreenRect,
    cursorWidth: Float,
    cursorHeight: Float,
): ScreenPoint {
    val maxX = (frame.right - cursorWidth).coerceAtLeast(frame.left)
    val maxY = (frame.bottom - cursorHeight).coerceAtLeast(frame.top)
    return ScreenPoint(
        x = hotspot.x.coerceIn(frame.left, maxX),
        y = hotspot.y.coerceIn(frame.top, maxY),
    )
}

/** 箭头热点始终留在远程画面内，同时尽量让整块控制器留在手机屏幕内。 */
internal fun clampVirtualMouseAnchor(
    proposed: ScreenPoint,
    frame: ScreenRect,
    viewportHeight: Float,
    controlHeight: Float,
    topInset: Float,
): ScreenPoint {
    if (frame.width <= 0f || frame.height <= 0f || viewportHeight <= 0f) return proposed
    val minY = max(frame.top, topInset)
    val maxY = min(frame.bottom, viewportHeight - controlHeight).coerceAtLeast(minY)
    return ScreenPoint(
        x = proposed.x.coerceIn(frame.left, frame.right),
        y = proposed.y.coerceIn(minY, maxY),
    )
}

/** 控制器右侧触到屏幕边缘时打开右侧控制区，避免面板遮住远端最右侧。 */
internal fun shouldDockVirtualMouseRight(
    anchorX: Float,
    controlWidth: Float,
    viewportWidth: Float,
): Boolean = anchorX + controlWidth >= viewportWidth

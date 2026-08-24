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
    /** 视口宽度（px）：null=同 frame.right（旧行为），非 null=面板右边缘最多贴视口右缘（完整可见）。 */
    viewportWidth: Float? = null,
): ScreenPoint {
    if (frame.width <= 0f || frame.height <= 0f || viewportHeight <= 0f) return proposed
    val minY = max(frame.top, topInset)
    val maxY = min(frame.bottom, viewportHeight - controlHeight).coerceAtLeast(minY)
    // 面板左边缘可超出视频帧右边界（挤入右侧控制区），但面板右边缘不超出视口右边界
    val maxX = viewportWidth ?: frame.right
    return ScreenPoint(
        // 左界 = 画面左缘与屏幕左缘的较大者：放大后画面左缘在屏幕外（<0），
        // 鼠标不能挪出屏幕（贴屏幕左缘触发左推）；未放大时画面左缘在屏幕内
        // （黑边），鼠标仍限制在画面内
        x = proposed.x.coerceIn(max(frame.left, 0f), maxX),
        y = proposed.y.coerceIn(minY, maxY),
    )
}

/** 右侧渐进挤开的换算结果：视频左移量 + 面板显示 anchor X。 */
internal data class VirtualMousePush(
    val overlapPx: Float,
    val anchorX: Float,
)

/**
 * 虚拟鼠标拖到右缘后的「ToDesk 式」渐进推开：anchor 钉在右缘（面板右边缘贴
 * 视口右缘，完整可见不滑出屏幕——放大态同样如此），继续右拖的过冲量累积为
 * 视频整体左移（左侧裁掉），满推时光标恰好够到视频右列；左拖先回弹视频、
 * 回完面板才跟随左移。
 *
 * 推量上限 = 把画面右缘推到面板左缘所需的平移量：未放大时画面右缘在视口
 * 右缘（恰为 controlWidth，与旧行为一致）；放大/平移后画面右缘可能在视口外，
 * 上限自动变大——鼠标贴近右边时画面持续平移过来，鼠标永不滑出屏幕
 * （用户反馈：放大后鼠标直接移出屏幕不符合预期）。
 *
 * @param rawAnchorX 未被右缘钳制的累积 anchor X（拖拽 delta 全程累加，
 *   调用方持久化——过冲若随 clamp 丢弃，overlap 每帧只剩当次 delta，
 *   视频原地抖动永远推不开：用户反馈拖到右缘卡住、点不到最右列）
 * @param unpushedFrameRight 未推开时的画面右缘（screenFrame(controlArea=0).right）
 */
internal fun computeVirtualMousePush(
    rawAnchorX: Float,
    viewportWidth: Float,
    controlWidth: Float,
    unpushedFrameRight: Float = viewportWidth,
): VirtualMousePush {
    if (viewportWidth <= 0f || controlWidth <= 0f) return VirtualMousePush(0f, rawAnchorX)
    val maxAnchorX = (viewportWidth - controlWidth).coerceAtLeast(0f)
    val pushCap = (unpushedFrameRight - maxAnchorX).coerceAtLeast(0f)
    val cappedRaw = rawAnchorX.coerceAtMost(maxAnchorX + pushCap)
    return VirtualMousePush(
        overlapPx = (cappedRaw - maxAnchorX).coerceIn(0f, pushCap),
        anchorX = cappedRaw.coerceAtMost(maxAnchorX),
    )
}

/** 控制器右侧触到屏幕边缘时打开右侧控制区，避免面板遮住远端最右侧。 */
internal fun shouldDockVirtualMouseRight(
    anchorX: Float,
    controlWidth: Float,
    viewportWidth: Float,
): Boolean = anchorX + controlWidth >= viewportWidth

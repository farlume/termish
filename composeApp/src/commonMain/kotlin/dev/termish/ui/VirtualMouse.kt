package dev.termish.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import dev.termish.ui.theme.ScreenControlDimens
import dev.termish.ui.theme.ScreenControlTokens
import kotlin.math.roundToInt
import kotlinx.coroutines.withTimeoutOrNull

/** 收起态：位于键盘按钮上方的虚拟鼠标入口。 */
@Composable
internal fun VirtualMouseButton(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(ScreenControlDimens.FloatingButton)
            .clip(RoundedCornerShape(ScreenControlDimens.FloatingButton))
            .background(ScreenControlTokens.FloatingBackground)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Mouse,
            contentDescription = contentDescription,
            tint = ScreenControlTokens.CursorOutline,
            modifier = Modifier.size(ScreenControlDimens.FloatingIcon),
        )
    }
}

/**
 * ToDesk 风格虚拟鼠标：上半部左右键，中间滚轮，下半部整体拖动区。
 * 箭头与面板是固定组合，拖动不会向远端发送鼠标移动事件。
 */
@Composable
internal fun VirtualMousePanel(
    position: Offset,
    strings: ScreenStrings,
    onClose: () -> Unit,
    onLeftClick: () -> Unit,
    onLeftDragStart: () -> Unit,
    onLeftDrag: (Offset) -> Unit,
    onLeftDragEnd: () -> Unit,
    onRightClick: () -> Unit,
    onScroll: (Int) -> Unit,
    onMovePanel: (Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val panelShape = RoundedCornerShape(percent = 42)

    val currentOnMovePanel by rememberUpdatedState(onMovePanel)
    val currentOnScroll by rememberUpdatedState(onScroll)
    val currentOnLeftClick by rememberUpdatedState(onLeftClick)
    val currentOnLeftDragStart by rememberUpdatedState(onLeftDragStart)
    val currentOnLeftDrag by rememberUpdatedState(onLeftDrag)
    val currentOnLeftDragEnd by rememberUpdatedState(onLeftDragEnd)

    Box(
        modifier
            .offset { IntOffset(position.x.roundToInt(), position.y.roundToInt()) }
            .width(
                ScreenControlDimens.MousePanelWidth +
                    ScreenControlDimens.MouseCloseGap +
                    ScreenControlDimens.MouseCloseButton,
            ).height(ScreenControlDimens.MousePanelHeight)
            .zIndex(220f),
    ) {
        Box(
            Modifier
                .size(ScreenControlDimens.MousePanelWidth, ScreenControlDimens.MousePanelHeight)
                .clip(panelShape)
                .background(ScreenControlTokens.MouseSurface)
                .border(ScreenControlDimens.MouseStroke, ScreenControlTokens.MouseStroke, panelShape),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(ScreenControlDimens.MouseTopButtonsHeight),
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .semantics {
                            contentDescription = strings.virtualMouseLeftClick
                            role = Role.Button
                            onClick {
                                currentOnLeftClick()
                                true
                            }
                        }.pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                down.consume()
                                var lastPosition = down.position
                                var released = false
                                var pointerGone = false
                                var initialDrag = Offset.Zero
                                var dragRequested = false
                                val resolvedBeforeLongPress =
                                    withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                        while (!released && !pointerGone && !dragRequested) {
                                            val event = awaitPointerEvent()
                                            val change = event.changes.firstOrNull { it.id == down.id }
                                            if (change == null) {
                                                pointerGone = true
                                                break
                                            }
                                            if (!change.pressed) {
                                                released = true
                                                break
                                            }
                                            val fromDown = change.position - down.position
                                            if (fromDown.getDistance() > viewConfiguration.touchSlop) {
                                                // 左键区本身就是“按住并拖”的入口：用户按下后
                                                // 直接移动应立即进入拖态，不能在长按超时前因
                                                // 越过 slop 反而取消整次手势。
                                                change.consume()
                                                initialDrag = fromDown
                                                lastPosition = change.position
                                                dragRequested = true
                                                break
                                            }
                                            lastPosition = change.position
                                        }
                                        true
                                    }
                                if (resolvedBeforeLongPress == null) dragRequested = true

                                when {
                                    released -> currentOnLeftClick()
                                    dragRequested && !pointerGone -> {
                                        currentOnLeftDragStart()
                                        try {
                                            if (initialDrag != Offset.Zero) {
                                                currentOnLeftDrag(initialDrag)
                                            }
                                            while (true) {
                                                val event = awaitPointerEvent()
                                                val change =
                                                    event.changes.firstOrNull { it.id == down.id }
                                                        ?: break
                                                if (!change.pressed) break
                                                val delta = change.position - lastPosition
                                                if (delta != Offset.Zero) {
                                                    change.consume()
                                                    currentOnLeftDrag(delta)
                                                    lastPosition = change.position
                                                }
                                            }
                                        } finally {
                                            currentOnLeftDragEnd()
                                        }
                                    }
                                    else -> {
                                        // 指针流被系统取消时消费到抬手，避免残留点击。
                                        if (!pointerGone) {
                                            while (true) {
                                                val event = awaitPointerEvent()
                                                val change = event.changes.firstOrNull { it.id == down.id }
                                                if (change == null || !change.pressed) break
                                                change.consume()
                                            }
                                        }
                                    }
                                }
                            }
                        },
                )
                Box(
                    Modifier
                        .width(ScreenControlDimens.MouseDivider)
                        .fillMaxSize()
                        .background(ScreenControlTokens.MouseStroke),
                )
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .semantics { contentDescription = strings.virtualMouseRightClick }
                        .clickable(onClick = onRightClick),
                )
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(ScreenControlDimens.MouseDivider)
                    .offset(y = ScreenControlDimens.MouseTopButtonsHeight)
                    .background(ScreenControlTokens.MouseStroke),
            )

            Box(
                Modifier
                    .fillMaxSize()
                    .padding(top = ScreenControlDimens.MouseTopButtonsHeight)
                    .semantics { contentDescription = strings.virtualMouseMovePanel }
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            currentOnMovePanel(dragAmount)
                        }
                    },
            ) {
                MousePanelGrip(
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }

        MouseScrollControl(
            strings = strings,
            onScroll = onScroll,
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .offset(
                        x =
                            (
                                ScreenControlDimens.MousePanelWidth -
                                    ScreenControlDimens.MouseScrollWidth
                            ) / 2,
                        y = ScreenControlDimens.MouseScrollTopOffset,
                    ).zIndex(6f),
        )

        Box(
            Modifier
                .offset(x = ScreenControlDimens.MousePanelWidth + ScreenControlDimens.MouseCloseGap)
                .size(ScreenControlDimens.MouseCloseButton)
                .clip(RoundedCornerShape(ScreenControlDimens.MouseCloseButton))
                .background(ScreenControlTokens.CloseBackground)
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = strings.virtualMouseClose,
                tint = ScreenControlTokens.CursorOutline,
                modifier = Modifier.size(ScreenControlDimens.FloatingIcon),
            )
        }
    }
}

@Composable
private fun MouseScrollControl(
    strings: ScreenStrings,
    onScroll: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val currentOnScroll by rememberUpdatedState(onScroll)
    val shape = RoundedCornerShape(ScreenControlDimens.MouseScrollWidth)
    Column(
        modifier
            .size(ScreenControlDimens.MouseScrollWidth, ScreenControlDimens.MouseScrollHeight)
            .clip(shape)
            .background(ScreenControlTokens.MouseSurface)
            .border(ScreenControlDimens.MouseStroke, ScreenControlTokens.MouseStroke, shape),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clickable { currentOnScroll(ScreenControlTokens.SCROLL_DIRECTION_UP) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.KeyboardArrowUp,
                contentDescription = strings.virtualMouseScrollUp,
                tint = ScreenControlTokens.MouseGlyph,
            )
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .semantics { contentDescription = strings.virtualMouseMove }
                .pointerInput(Unit) {
                    val step = with(density) { ScreenControlDimens.MouseScrollStep.toPx() }
                    var accumulated = 0f
                    detectDragGestures(
                        onDragStart = { accumulated = 0f },
                    ) { change, amount ->
                        change.consume()
                        accumulated += amount.y
                        val lines = (accumulated / step).toInt()
                        if (lines != 0) {
                            currentOnScroll(-lines)
                            accumulated -= lines * step
                        }
                    }
                },
        ) {
            val stroke = ScreenControlDimens.MouseScrollLine.toPx()
            val lineWidth = size.width * 0.42f
            repeat(3) { index ->
                val y = size.height * (0.36f + index * 0.14f)
                drawLine(
                    color = ScreenControlTokens.MouseGlyph,
                    start = Offset((size.width - lineWidth) / 2f, y),
                    end = Offset((size.width + lineWidth) / 2f, y),
                    strokeWidth = stroke,
                )
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clickable { currentOnScroll(ScreenControlTokens.SCROLL_DIRECTION_DOWN) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = strings.virtualMouseScrollDown,
                tint = ScreenControlTokens.MouseGlyph,
            )
        }
    }
}

@Composable
private fun MousePanelGrip(
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier
            .fillMaxWidth()
            .height(ScreenControlDimens.MousePanelGripHeight),
    ) {
        val radius = ScreenControlDimens.MouseGripDot.toPx() / 2f
        val gap = radius * 3f
        val center = Offset(size.width / 2f, size.height / 2f)
        repeat(2) { row ->
            repeat(4) { column ->
                drawCircle(
                    color = ScreenControlTokens.MouseGlyph,
                    radius = radius,
                    center =
                        Offset(
                            center.x + (column - 1.5f) * gap,
                            center.y + (row - 0.5f) * gap,
                        ),
                )
            }
        }
    }
}

/**
 * 画面内的本地箭头。尖端必须精确落在热点（点击位置）上——热点可到画面四边，
 * 靠近边缘时箭头尾巴超出画布的部分由 clip 裁掉（用户反馈：之前图形整体内收，
 * 尖端到不了边缘，点不到最边上一列像素）。
 */
@Composable
internal fun VirtualMouseCursor(
    frame: ScreenRect,
    cursor: ScreenPoint,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.fillMaxSize().zIndex(230f)) {
        if (frame.width <= 0f || frame.height <= 0f) return@Canvas
        val cursorWidth = ScreenControlDimens.MouseCursorWidth.toPx()
        val cursorHeight = ScreenControlDimens.MouseCursorHeight.toPx()
        val hotspot = cursorHotspot(cursor, frame)
        // 尖端 = 热点，不做图形内收；尾巴超出画布（边缘/被推开的画面外侧）裁掉
        clipRect(0f, 0f, size.width, size.height) {
            val path =
                Path().apply {
                    moveTo(hotspot.x, hotspot.y)
                    lineTo(hotspot.x, hotspot.y + cursorHeight * 0.78f)
                    lineTo(hotspot.x + cursorWidth * 0.25f, hotspot.y + cursorHeight * 0.59f)
                    lineTo(hotspot.x + cursorWidth * 0.47f, hotspot.y + cursorHeight)
                    lineTo(hotspot.x + cursorWidth * 0.66f, hotspot.y + cursorHeight * 0.90f)
                    lineTo(hotspot.x + cursorWidth * 0.44f, hotspot.y + cursorHeight * 0.53f)
                    lineTo(hotspot.x + cursorWidth * 0.82f, hotspot.y + cursorHeight * 0.53f)
                    close()
                }
            drawPath(path, color = ScreenControlTokens.CursorFill)
            drawPath(
                path,
                color = ScreenControlTokens.CursorOutline,
                style = Stroke(width = ScreenControlDimens.MouseStroke.toPx()),
            )
        }
    }
}

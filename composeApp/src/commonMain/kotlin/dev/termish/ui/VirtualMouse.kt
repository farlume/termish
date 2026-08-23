package dev.termish.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import dev.termish.ui.theme.ScreenControlDimens
import dev.termish.ui.theme.ScreenControlTokens
import kotlin.math.roundToInt

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
    onRightClick: () -> Unit,
    onScroll: (Int) -> Unit,
    onMovePanel: (Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val panelShape = RoundedCornerShape(percent = 42)

    val currentOnMovePanel by rememberUpdatedState(onMovePanel)
    val currentOnScroll by rememberUpdatedState(onScroll)

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
                        .semantics { contentDescription = strings.virtualMouseLeftClick }
                        .clickable(onClick = onLeftClick),
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

/** 画面内的本地箭头；热点可抵达边缘，图形原点会向内收，保证箭头完整可见。 */
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
        val origin = cursorVisualOrigin(hotspot, frame, cursorWidth, cursorHeight)
        // 确保箭头不超出画布边界（frame 左侧可能因右侧控制区挤开而为负）
        val clampedOrigin =
            ScreenPoint(
                x = origin.x.coerceIn(0f, (size.width - cursorWidth).coerceAtLeast(0f)),
                y = origin.y.coerceIn(0f, (size.height - cursorHeight).coerceAtLeast(0f)),
            )
        val path =
            Path().apply {
                moveTo(clampedOrigin.x, clampedOrigin.y)
                lineTo(clampedOrigin.x, clampedOrigin.y + cursorHeight * 0.78f)
                lineTo(clampedOrigin.x + cursorWidth * 0.25f, clampedOrigin.y + cursorHeight * 0.59f)
                lineTo(clampedOrigin.x + cursorWidth * 0.47f, clampedOrigin.y + cursorHeight)
                lineTo(clampedOrigin.x + cursorWidth * 0.66f, clampedOrigin.y + cursorHeight * 0.90f)
                lineTo(clampedOrigin.x + cursorWidth * 0.44f, clampedOrigin.y + cursorHeight * 0.53f)
                lineTo(clampedOrigin.x + cursorWidth * 0.82f, clampedOrigin.y + cursorHeight * 0.53f)
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

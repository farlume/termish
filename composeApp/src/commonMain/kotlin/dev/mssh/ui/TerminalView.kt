package dev.mssh.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import dev.mssh.term.CellAttr
import dev.mssh.term.CharWidth
import dev.mssh.term.DEFAULT_BG
import dev.mssh.term.DEFAULT_FG
import dev.mssh.term.TerminalBuffer
import dev.mssh.term.TerminalCell
import dev.mssh.term.TerminalLine
import dev.mssh.term.TerminalPalette
import dev.mssh.ui.theme.TerminalTheme

private fun cellColor(c: Int, theme: TerminalTheme): Color {
    val idx = TerminalPalette.BASIC_16.indexOf(c)
    return if (idx >= 0) theme.ansi(idx) else Color(0xFF000000.toInt() or c)
}

private fun cellColors(cell: TerminalCell, theme: TerminalTheme): Pair<Color, Color> {
    val fg = if (cell.fg == DEFAULT_FG) theme.foreground() else cellColor(cell.fg, theme)
    val bg = if (cell.bg == DEFAULT_BG) theme.background() else cellColor(cell.bg, theme)
    return if (cell.attrs and CellAttr.INVERSE != 0) Pair(bg, fg) else Pair(fg, bg)
}

private fun StringBuilder.appendCodePoint(cp: Int) {
    if (cp <= 0xFFFF) append(cp.toChar())
    else {
        val x = cp - 0x10000
        append((0xD800 + (x shr 10)).toChar())
        append((0xDC00 + (x and 0x3FF)).toChar())
    }
}

private fun codePointToString(cp: Int): String = StringBuilder().appendCodePoint(cp).toString()

private fun currentStartAbs(buffer: TerminalBuffer, scrollOffset: Int): Int {
    val scrollback = buffer.scrollbackSize()
    val offset = scrollOffset.coerceIn(0, scrollback)
    return (buffer.totalLines() - buffer.rows - offset).coerceAtLeast(0)
}

@Composable
fun TerminalView(
    controller: TerminalController,
    theme: TerminalTheme,
    fontSizeSp: Float,
    onFocusKeyboard: () -> Unit,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()

    var scrollOffset by remember { mutableStateOf(0) }
    var fontSizeState by remember { mutableFloatStateOf(fontSizeSp) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    val fontSize = fontSizeState.sp
    val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize)
    val sample = remember(fontSizeState) { textMeasurer.measure("W", style) }
    val cellW = sample.size.width.toFloat()
    val cellH = (sample.size.height.toFloat() * 1.2f).coerceAtLeast(1f)
    val textTop = (cellH - sample.size.height) / 2f

    val buffer = controller.buffer

    // 尺寸或字号变化时重新计算行列并同步 PTY
    LaunchedEffect(canvasSize, fontSizeState) {
        val cols = (canvasSize.width / cellW).toInt().coerceAtLeast(1)
        val rows = (canvasSize.height / cellH).toInt().coerceAtLeast(1)
        controller.resize(cols, rows, canvasSize.width, canvasSize.height)
    }

    Canvas(
        modifier
            .onSizeChanged { size ->
                canvasSize = size
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onFocusKeyboard() },
                    onLongPress = { pos ->
                        val startAbsNow = currentStartAbs(buffer, scrollOffset)
                        val col = (pos.x / cellW).toInt().coerceIn(0, buffer.cols - 1)
                        val row = (pos.y / cellH).toInt().coerceIn(0, buffer.rows - 1)
                        val absRow = startAbsNow + row
                        controller.selection.start(absRow, col)
                        controller.selection.extend(absRow, buffer.cols - 1)
                        val text = controller.selection.selectedText()
                        if (text.isNotEmpty()) {
                            onCopy(text)
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    // 向上拖动（dragAmount.y > 0）查看回看
                    val delta = (dragAmount.y / cellH).toInt()
                    if (delta != 0) {
                        scrollOffset = (scrollOffset + delta).coerceIn(0, buffer.scrollbackSize())
                    }
                }
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ ->
                    fontSizeState = (fontSizeState * zoom).coerceIn(6f, 40f)
                }
            },
    ) {
        // 观察重绘序号触发 redraw（否则 Compose 会因 draw 内容未变而跳过重绘）
        @Suppress("UNUSED_VARIABLE")
        val redraw = controller.frame

        // 每次绘制都基于最新 buffer/滚动位置计算可视区
        val scrollback = buffer.scrollbackSize()
        val offset = scrollOffset.coerceIn(0, scrollback)
        val totalLines = buffer.totalLines()
        val startAbs = (totalLines - buffer.rows - offset).coerceAtLeast(0)

        // 背景（alpha 恒为 1，仅用于消费 redraw 以建立重绘观察）
        drawRect(theme.background().copy(alpha = if (redraw >= 0L) 1f else 1f))

        val lines = ArrayList<TerminalLine>(buffer.rows)
        for (r in 0 until buffer.rows) {
            val abs = startAbs + r
            lines.add(if (abs < totalLines) buffer.absLine(abs) else TerminalLine(buffer.cols))
        }

        // 背景色 run
        for (r in lines.indices) {
            val line = lines[r]
            val y = r * cellH
            var i = 0
            while (i < buffer.cols) {
                val cell = line.cells[i]
                val bgRaw = cell.bg
                var j = i
                while (j < buffer.cols && line.cells[j].bg == bgRaw) j++
                if (bgRaw != DEFAULT_BG) {
                    val (_, bgColor) = cellColors(cell, theme)
                    drawRect(
                        color = bgColor,
                        topLeft = Offset(i * cellW, y),
                        size = Size((j - i) * cellW, cellH),
                    )
                }
                i = j
            }
        }

        // 文本 run
        for (r in lines.indices) {
            val line = lines[r]
            val y = r * cellH
            var i = 0
            while (i < buffer.cols) {
                val cell = line.cells[i]
                if (cell.isWideTail) { i++; continue }
                if (cell.codePoint == ' '.code && cell.attrs and (CellAttr.UNDERLINE or CellAttr.INVERSE) == 0) { i++; continue }

                val (fgColor, _) = cellColors(cell, theme)
                val runStyle = style.copy(color = fgColor)
                var j = i
                val sb = StringBuilder()
                while (j < buffer.cols) {
                    val c2 = line.cells[j]
                    if (c2.isWideTail || c2.fg != cell.fg || c2.attrs != cell.attrs) break
                    if (CharWidth.wcwidth(c2.codePoint) != 1) break
                    if (c2.codePoint == ' '.code && c2.attrs and (CellAttr.UNDERLINE or CellAttr.INVERSE) == 0) break
                    sb.appendCodePoint(c2.codePoint)
                    j++
                }
                if (sb.isNotEmpty()) {
                    drawText(
                        textMeasurer = textMeasurer,
                        text = sb.toString(),
                        topLeft = Offset(i * cellW, y + textTop),
                        style = runStyle,
                    )
                }
                // 宽字符单独绘制
                if (j < buffer.cols && !line.cells[j].isWideTail && CharWidth.wcwidth(line.cells[j].codePoint) == 2) {
                    val wide = line.cells[j]
                    drawText(
                        textMeasurer = textMeasurer,
                        text = codePointToString(wide.codePoint),
                        topLeft = Offset(j * cellW, y + textTop),
                        style = style.copy(color = cellColors(wide, theme).first),
                    )
                    j += 2
                }
                i = j
            }
        }

        // 下划线
        for (r in lines.indices) {
            val line = lines[r]
            val y = r * cellH
            var i = 0
            while (i < buffer.cols) {
                val cell = line.cells[i]
                if (cell.attrs and CellAttr.UNDERLINE != 0 && !cell.isWideTail) {
                    var j = i
                    while (j < buffer.cols && line.cells[j].attrs and CellAttr.UNDERLINE != 0 && !line.cells[j].isWideTail) j++
                    val (fgColor, _) = cellColors(cell, theme)
                    drawRect(
                        color = fgColor,
                        topLeft = Offset(i * cellW, y + cellH - 2f),
                        size = Size((j - i) * cellW, 1.5f),
                    )
                    i = j
                } else i++
            }
        }

        // 选择高亮
        if (controller.selection.isActive) {
            val sel = controller.selection
            val selTop = minOf(sel.startRow, sel.endRow)
            val selBottom = maxOf(sel.startRow, sel.endRow)
            for (abs in selTop..selBottom) {
                val r = abs - startAbs
                if (r !in 0 until buffer.rows) continue
                val fromCol = if (abs == sel.startRow && abs == sel.endRow) minOf(sel.startCol, sel.endCol)
                else if (abs == sel.startRow) sel.startCol
                else if (abs == sel.endRow) sel.endCol
                else -1
                if (fromCol == -1) {
                    drawRect(theme.selection(), Offset(0f, r * cellH), Size(buffer.cols * cellW, cellH))
                } else {
                    val toCol = if (abs == sel.endRow) maxOf(sel.startCol, sel.endCol) else buffer.cols - 1
                    drawRect(
                        theme.selection(),
                        Offset(fromCol * cellW, r * cellH),
                        Size((toCol - fromCol + 1) * cellW, cellH),
                    )
                }
            }
        }

        // 光标
        val cursorAbs = buffer.absCursorRow()
        val cursorScreenRow = cursorAbs - startAbs
        if (buffer.cursorVisible && cursorScreenRow in 0 until buffer.rows) {
            drawRect(
                color = theme.cursor(),
                topLeft = Offset(buffer.cursorCol * cellW, cursorScreenRow * cellH),
                size = Size(cellW, cellH),
            )
            // 光标下的字符反色显示
            if (cursorAbs in 0 until totalLines) {
                val line = buffer.absLine(cursorAbs)
                if (buffer.cursorCol < buffer.cols) {
                    val cell = line.cells[buffer.cursorCol]
                    if (!cell.isWideTail && cell.codePoint != ' '.code) {
                        val (fg, bg) = cellColors(cell, theme)
                        drawText(
                            textMeasurer = textMeasurer,
                            text = codePointToString(cell.codePoint),
                            topLeft = Offset(buffer.cursorCol * cellW, cursorScreenRow * cellH + textTop),
                            style = style.copy(color = bg),
                        )
                    }
                }
            }
        }
    }
}

package dev.mssh.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
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
import dev.mssh.util.monospaceFontFamily

private fun cellColor(c: Int, theme: TerminalTheme): Color {
    val idx = TerminalPalette.BASIC_16.indexOf(c)
    return if (idx >= 0) theme.ansi(idx) else Color(0xFF000000.toInt() or c)
}

private fun cellColors(cell: TerminalCell, theme: TerminalTheme): Pair<Color, Color> {
    // xterm 惯例：BOLD + 基本 8 色 → 提亮到 8-15
    val fgIdx = if (cell.attrs and CellAttr.BOLD != 0 && cell.fg in 0..7) cell.fg + 8 else cell.fg
    val fg = if (cell.fg == DEFAULT_FG) theme.foreground() else cellColor(fgIdx, theme)
    val bg = if (cell.bg == DEFAULT_BG) theme.background() else cellColor(cell.bg, theme)
    return if (cell.attrs and CellAttr.INVERSE != 0) Pair(bg, fg) else Pair(fg, bg)
}

private fun runFontWeight(attrs: Int): FontWeight? =
    if (attrs and CellAttr.BOLD != 0) FontWeight.Bold else null

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
    targetCols: Int = 0,
    /** 键盘/工具栏覆盖画布底部的高度（px）：用于向上平移画布保证光标可见。 */
    coveredBottomPx: Float = 0f,
    onFocusKeyboard: () -> Unit,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()

    var scrollOffset by remember { mutableStateOf(0) }
    // 滚动小数累加器（按行换算）
    var scrollAccum by remember { mutableFloatStateOf(0f) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    val fontFamily = monospaceFontFamily()
    // 目标列数模式：字形宽度与字号成线性关系，用 12sp 参考测量反算所需字号
    val effectiveFontSizeSp = remember(canvasSize.width, targetCols, fontSizeSp, fontFamily) {
        if (targetCols > 0 && canvasSize.width > 0) {
            val ref = textMeasurer.measure("0".repeat(16), TextStyle(fontFamily = fontFamily, fontSize = 12.sp))
            val refCellW = ref.size.width.toFloat() / 16f
            val desiredCellW = canvasSize.width.toFloat() / targetCols
            (12f * desiredCellW / refCellW).coerceIn(4f, 32f)
        } else fontSizeSp
    }

    val fontSize = effectiveFontSizeSp.sp
    val style = TextStyle(fontFamily = fontFamily, fontSize = fontSize)
    // 用较长采样串测单格宽度：避免小字号下单字符宽度像素取整带来的累积误差
    val sample = remember(effectiveFontSizeSp, fontFamily) { textMeasurer.measure("0".repeat(16), style) }
    val cellW = (sample.size.width.toFloat() / 16f).coerceAtLeast(1f)
    val cellH = (sample.size.height.toFloat() * 1.2f).coerceAtLeast(1f)
    val textTop = (cellH - sample.size.height) / 2f

    val buffer = controller.buffer
    val latestCanvasSize by rememberUpdatedState(canvasSize)

    // 键盘/工具栏覆盖画布底部时向上平移，保证光标可见（不改变 PTY 尺寸，
    // 避免全屏程序随键盘弹收反复重排）；用户回看滚动时不平移。
    // frame 每次输出自增，读取它以在输出后重算平移。
    @Suppress("UNUSED_VARIABLE")
    val frame = controller.frame
    // 触摸 → 终端鼠标事件（X10/SGR）：herdr/vim/htop 等 TUI 开启鼠标上报后，
    // 触摸映射为左键按下/拖拽/释放，滚动手势映射为滚轮。坐标为 1-based 格坐标。
    fun sendMouseEvent(btn: Int, col: Int, row: Int, release: Boolean) {
        val c = (col + 1).coerceIn(1, buffer.cols)
        val r = (row + 1).coerceIn(1, buffer.rows)
        if (buffer.mouseSgr) {
            controller.sendText("\u001b[<$btn;$c;$r${if (release) "m" else "M"}")
        } else {
            // X10：按下发按钮号，释放发 3；坐标 32 偏移字节，上限 223
            if (c > 223 || r > 223) return
            val b = if (release) 3 else btn
            controller.sendBytes(
                byteArrayOf(0x1b, '['.code.toByte(), 'M'.code.toByte(), (32 + b).toByte(), (32 + c).toByte(), (32 + r).toByte())
            )
        }
    }
    fun pointerCell(pos: Offset): Pair<Int, Int> =
        ((pos.x / cellW).toInt().coerceIn(0, buffer.cols - 1)) to
            ((pos.y / cellH).toInt().coerceIn(0, buffer.rows - 1))

    // 最近指针位置（鼠标模式下滚轮事件的落点）
    var lastPointer by remember { mutableStateOf(Offset.Zero) }

    val panUp = run {
        if (scrollOffset != 0 || canvasSize.height <= 0 || coveredBottomPx <= 0f) return@run 0f
        val cursorBottomY = (buffer.absCursorRow() - currentStartAbs(buffer, scrollOffset) + 1) * cellH
        val visibleBottomY = canvasSize.height.toFloat() - coveredBottomPx
        if (cursorBottomY > visibleBottomY) {
            (cursorBottomY - visibleBottomY).coerceAtMost(canvasSize.height.toFloat())
        } else 0f
    }

    // 尺寸或字号变化时重新计算行列并同步 PTY
    LaunchedEffect(canvasSize, effectiveFontSizeSp) {
        val cols = (canvasSize.width / cellW).toInt().coerceAtLeast(1)
        val rows = (canvasSize.height / cellH).toInt().coerceAtLeast(1)
        controller.resize(cols, rows, canvasSize.width, canvasSize.height)
    }

    Canvas(
        modifier
            .graphicsLayer { translationY = -panUp }
            .onSizeChanged { size ->
                canvasSize = size
            }
            // 触摸 → 鼠标事件：TUI 开启上报（1000/1002/1003）时接管手势，
            // 否则不消费事件，保持聚焦键盘/选择/滚动回看的默认行为
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (buffer.mouseTracking <= 0) return@awaitEachGesture
                    down.consume()
                    var (col, row) = pointerCell(down.position)
                    sendMouseEvent(0, col, row, release = false)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.first()
                        change.consume()
                        lastPointer = change.position
                        val (nc, nr) = pointerCell(change.position)
                        if (nc != col || nr != row) {
                            col = nc; row = nr
                            // 拖拽移动（1002/1003 才上报移动）
                            if (buffer.mouseTracking >= 1002) {
                                sendMouseEvent(32, col, row, release = false)
                            }
                        }
                        if (change.changedToUp()) {
                            sendMouseEvent(0, col, row, release = true)
                            break
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    // 点击不再拉起键盘（统一走工具栏 ⌨ 按钮，避免与 TUI 鼠标点击冲突）
                    onDoubleTap = { pos ->
                        // 双击选词并复制
                        val startAbsNow = currentStartAbs(buffer, scrollOffset)
                        val col = (pos.x / cellW).toInt().coerceIn(0, buffer.cols - 1)
                        val row = (pos.y / cellH).toInt().coerceIn(0, buffer.rows - 1)
                        val absRow = startAbsNow + row
                        if (absRow < buffer.totalLines()) {
                            val line = buffer.absLine(absRow)
                            fun isWordCell(idx: Int): Boolean {
                                val c = line.cells[idx]
                                if (c.isWideTail) return false
                                val cp = c.codePoint
                                return cp < 128 && (cp.toChar().isLetterOrDigit() || cp.toChar() in "._-~/:@%")
                            }
                            if (isWordCell(col)) {
                                var s = col
                                while (s > 0 && isWordCell(s - 1)) s--
                                var e = col
                                while (e < buffer.cols - 1 && isWordCell(e + 1)) e++
                                controller.selection.start(absRow, s)
                                controller.selection.extend(absRow, e)
                                val text = controller.selection.selectedText()
                                if (text.isNotEmpty()) onCopy(text)
                            }
                        }
                    },
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
            // 滚动回看：scrollable 自带 fling 惯性衰减；
            // delta 为手指拖动方向（下滑 delta>0 → 内容跟手下移 → 看历史 → scrollOffset 增大）
            .scrollable(
                orientation = Orientation.Vertical,
                state = rememberScrollableState { delta ->
                    scrollAccum += delta
                    val rows = (scrollAccum / cellH).toInt()
                    if (rows != 0) {
                        if (buffer.mouseTracking > 0) {
                            // TUI 鼠标模式：滚动映射为滚轮事件（64=上 65=下）
                            val btn = if (rows > 0) 65 else 64
                            val (wc, wr) = pointerCell(lastPointer)
                            repeat(kotlin.math.abs(rows)) { sendMouseEvent(btn, wc, wr, release = false) }
                        } else {
                            scrollOffset = (scrollOffset + rows).coerceIn(0, buffer.scrollbackSize())
                        }
                        scrollAccum -= rows * cellH
                    }
                    delta
                },
            )
            // 滚动条拖动：仅右边缘命中时消费事件，其余交给 scrollable。
            // 鼠标模式下让位（TUI 的 UI 元素可能在右边缘，如 herdr 的 tab 按钮）；
            // 默认 requireUnconsumed=true：上游鼠标处理已消费的事件不再触发。
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (buffer.mouseTracking > 0) return@awaitEachGesture
                    val barZone = size.width - 24.dp.toPx()
                    if (down.position.x >= barZone && buffer.scrollbackSize() > 0) {
                        drag(down.id) { change ->
                            val sb = buffer.scrollbackSize()
                            val h = latestCanvasSize.height
                            if (sb > 0 && h > 0) {
                                val total = buffer.totalLines().toFloat()
                                val trackH = h.toFloat()
                                val thumbH = (buffer.rows / total * trackH).coerceAtLeast(24f)
                                val ratio = (change.position.y / (trackH - thumbH)).coerceIn(0f, 1f)
                                scrollOffset = (ratio * sb).toInt().coerceIn(0, sb)
                            }
                            change.consume()
                        }
                    }
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

        // 键盘弹起/字号变化时，canvas 尺寸与 buffer 行列数存在一帧的瞬态不一致，
        // 越界绘制文本会导致 drawText 内部约束为负而崩溃，这里统一跳过越界文本。
        fun drawTextInBounds(text: String, topLeft: Offset, textStyle: TextStyle) {
            if (topLeft.x < 0f || topLeft.y < 0f || topLeft.x >= size.width || topLeft.y >= size.height) return
            drawText(textMeasurer = textMeasurer, text = text, topLeft = topLeft, style = textStyle)
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
                val runStyle = style.copy(color = fgColor, fontWeight = runFontWeight(cell.attrs))
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
                    drawTextInBounds(sb.toString(), Offset(i * cellW, y + textTop), runStyle)
                }
                // 宽字符单独绘制
                if (j < buffer.cols && !line.cells[j].isWideTail && CharWidth.wcwidth(line.cells[j].codePoint) == 2) {
                    val wide = line.cells[j]
                    drawTextInBounds(
                        codePointToString(wide.codePoint),
                        Offset(j * cellW, y + textTop),
                        style.copy(color = cellColors(wide, theme).first, fontWeight = runFontWeight(wide.attrs)),
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
                        drawTextInBounds(
                            codePointToString(cell.codePoint),
                            Offset(buffer.cursorCol * cellW, cursorScreenRow * cellH + textTop),
                            style.copy(color = bg, fontWeight = runFontWeight(cell.attrs)),
                        )
                    }
                }
            }
        }

        // 滚动条（有历史时显示在右侧，可拖动）
        if (scrollback > 0) {
            val barW = 6f
            val barX = size.width - barW - 4f
            val trackH = (size.height - 4f).coerceAtLeast(1f)
            val total = totalLines.toFloat().coerceAtLeast(buffer.rows.toFloat())
            val thumbH = (buffer.rows / total * trackH).coerceAtLeast(24f)
            val thumbY = (offset.toFloat() / scrollback * (trackH - thumbH)).coerceIn(0f, trackH - thumbH)
            drawRect(theme.foreground().copy(alpha = 0.12f), Offset(barX, 2f), Size(barW, trackH))
            drawRect(theme.foreground().copy(alpha = 0.45f), Offset(barX, 2f + thumbY), Size(barW, thumbH))
        }
    }
}

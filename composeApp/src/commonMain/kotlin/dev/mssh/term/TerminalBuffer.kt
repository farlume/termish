package dev.mssh.term

/** 单个字符单元。 */
class TerminalCell {
    var codePoint: Int = ' '.code
    var attrs: Int = 0
    var fg: Int = DEFAULT_FG
    var bg: Int = DEFAULT_BG

    /** 该单元是前一个宽字符（占 2 列）的“尾巴”。 */
    var isWideTail: Boolean = false

    val isWide: Boolean get() = CharWidth.wcwidth(codePoint) > 1

    fun clear() {
        codePoint = ' '.code
        attrs = 0
        fg = DEFAULT_FG
        bg = DEFAULT_BG
        isWideTail = false
    }

    fun copyFrom(o: TerminalCell) {
        codePoint = o.codePoint
        attrs = o.attrs
        fg = o.fg
        bg = o.bg
        isWideTail = o.isWideTail
    }
}

/** 一行字符单元。 */
class TerminalLine(val cols: Int) {
    var cells = Array(cols) { TerminalCell() }

    /** 该行是否因自动换行而开始（用于选择 / 回看等）。 */
    var wrapped: Boolean = false

    fun clear() {
        wrapped = false
        for (c in cells) c.clear()
    }

    /** 整行是否为默认空白（无字符/颜色/属性），resize 收缩时用于安全丢弃。 */
    fun isBlank(): Boolean {
        for (c in cells) {
            if (c.codePoint != ' '.code || c.attrs != 0 || c.fg != DEFAULT_FG || c.bg != DEFAULT_BG) return false
        }
        return true
    }
}

/**
 * 终端屏幕缓冲：双缓冲（普通屏 + 备用屏）、滚动回看、光标与模式。
 *
 * 普通屏：一个 [ArrayDeque]，末尾 [rows] 行是可见屏幕，之前的行是滚动回看
 * （上限 [maxScrollbackLines]）。备用屏：固定 rows×cols，无回看（vim/tmux 等全屏程序）。
 */
class TerminalBuffer(
    var cols: Int,
    var rows: Int,
    private val maxScrollbackLines: Int = 10_000,
) {
    private val normal = ArrayDeque<TerminalLine>()
    private var alt = Array(rows) { TerminalLine(cols) }

    var altScreen: Boolean = false
        private set

    var cursorRow: Int = 0
    var cursorCol: Int = 0
    var cursorVisible: Boolean = true

    var savedCursorRow: Int = 0
    var savedCursorCol: Int = 0

    var scrollTop: Int = 0
    var scrollBottom: Int = rows - 1

    var autoWrap: Boolean = true
    var originMode: Boolean = false
    var insertMode: Boolean = false
    var applicationCursorKeys: Boolean = false
    /** bracketed paste（DECSET 2004）：开启时粘贴内容需包在 ESC[200~ ... ESC[201~ 中。 */
    var bracketedPaste: Boolean = false

    /** 鼠标上报模式：0=关闭，1000=X10 点击，1002=按钮事件拖拽，1003=全事件。 */
    var mouseTracking: Int = 0
    /** 鼠标坐标格式：true=SGR(1006)，false=X10 字节偏移。 */
    var mouseSgr: Boolean = false

    /** 默认前景/背景（RGB），由 UI 按当前主题设置，用于应答 OSC 10/11 颜色查询。 */
    var defaultFgRgb: Int = 0xd5d8de
    var defaultBgRgb: Int = 0x0e0f13
    var applicationKeypad: Boolean = false

    var tabStops: BooleanArray = defaultTabStops(cols)

    /** 当前显示使用的字符集（DEC 特殊图形等）。 */
    var currentCharset: Charset = Charset.ASCII

    /** 变更计数：UI 用于跳过重绘。 */
    var changeSequence: Long = 0
        private set

    private fun markChanged() {
        changeSequence++
    }

    init {
        // 初始化普通屏为一个空屏幕
        repeat(rows) { normal.addLast(TerminalLine(cols)) }
    }

    enum class Charset { ASCII, DEC_SPECIAL }

    // ---------- 行访问 ----------

    private fun lineIndex(row: Int): Int = normal.size - rows + row

    private fun normalLine(row: Int): TerminalLine = normal[lineIndex(row)]

    fun altLine(row: Int): TerminalLine = alt[row]

    fun lineAt(row: Int): TerminalLine =
        if (altScreen) alt[row] else normalLine(row)

    fun visibleLineCount(): Int = rows

    /** 滚动回看行数（普通屏）或 0（备用屏）。 */
    fun scrollbackSize(): Int = if (altScreen) 0 else normal.size - rows

    /** 可见行（上→下），供渲染使用。 */
    fun visibleLines(): List<TerminalLine> {
        return if (altScreen) {
            alt.toList()
        } else {
            val start = normal.size - rows
            (start until normal.size).map { normal[it] }
        }
    }

    /** 绝对行总数（回看 + 可见屏）。 */
    fun totalLines(): Int = if (altScreen) rows else normal.size

    /** 绝对行（索引 0 = 最老的滚动回看行）。 */
    fun absLine(index: Int): TerminalLine = if (altScreen) alt[index] else normal[index]

    /** 光标的绝对行号。 */
    fun absCursorRow(): Int = if (altScreen) cursorRow else (normal.size - rows) + cursorRow

    /** 渲染某列：解析有效前景色（考虑 inverse 等）。 */
    fun resolveFg(cell: TerminalCell, defaultFg: Int, defaultBg: Int): Int {
        val fg = if (cell.fg == DEFAULT_FG) defaultFg else cell.fg
        val bg = if (cell.bg == DEFAULT_BG) defaultBg else cell.bg
        return when {
            cell.attrs and CellAttr.INVERSE != 0 -> bg
            else -> fg
        }
    }

    fun resolveBg(cell: TerminalCell, defaultFg: Int, defaultBg: Int): Int {
        val fg = if (cell.fg == DEFAULT_FG) defaultFg else cell.fg
        val bg = if (cell.bg == DEFAULT_BG) defaultBg else cell.bg
        return when {
            cell.attrs and CellAttr.INVERSE != 0 -> fg
            else -> bg
        }
    }

    // ---------- 写操作 ----------

    /** 在当前光标处写一个码点（宽字符占 2 列），必要时自动换行/滚动。 */
    fun putChar(cp: Int) {
        val width = if (cp == 0) 1 else CharWidth.wcwidth(cp)
        val line = lineAt(cursorRow)
        val col = cursorCol

        // 落点在宽字符尾巴上：先清掉头，避免残留半个宽字符
        if (col > 0 && line.cells[col].isWideTail) line.cells[col - 1].clear()

        if (width == 2) {
            if (cols < 2) { writeNarrow(cp); return }
            // 宽字符：若处于行末，先换行
            if (col >= cols - 1) {
                newline()
                putChar(cp)
                return
            }
            val c = line.cells[col]
            c.clear()
            c.codePoint = cp
            c.attrs = currentAttrs
            c.fg = currentFg
            c.bg = currentBg
            val tail = line.cells[col + 1]
            tail.clear()
            // 尾巴必须继承头的颜色/属性：否则宽字符右半格露出默认背景，
            // 在带底色的行（如 pi 的消息条）上表现为黑块盖住半个字
            tail.attrs = currentAttrs
            tail.fg = currentFg
            tail.bg = currentBg
            tail.isWideTail = true
            // 尾巴盖掉了原宽字符的头：清掉它原来尾巴的标记，避免越雷悬空
            if (col + 2 < cols && line.cells[col + 2].isWideTail) line.cells[col + 2].isWideTail = false
            cursorCol = col + 2
            markChanged()
            return
        }

        writeNarrow(cp)
    }

    private fun writeNarrow(cp: Int) {
        if (insertMode) {
            insertCells(1)
        }
        val line = lineAt(cursorRow)
        // 落点在宽字符尾巴上：先清掉头
        if (cursorCol > 0 && line.cells[cursorCol].isWideTail) line.cells[cursorCol - 1].clear()
        val c = line.cells[cursorCol]
        c.clear()
        c.codePoint = cp
        c.attrs = currentAttrs
        c.fg = currentFg
        c.bg = currentBg
        // 覆盖宽字符头时清掉尾巴标记
        if (cursorCol + 1 < cols && line.cells[cursorCol + 1].isWideTail) {
            line.cells[cursorCol + 1].isWideTail = false
        }

        if (cursorCol == cols - 1) {
            // 行末：标记 wrap，准备换行（延迟到下一个字符）
            pendingWrap = true
        } else {
            cursorCol++
        }
        markChanged()
    }

    var pendingWrap: Boolean = false

    /** 当前写入使用的属性/颜色（由解析器在 SGR 时更新）。 */
    var currentAttrs: Int = 0
    var currentFg: Int = DEFAULT_FG
    var currentBg: Int = DEFAULT_BG

    /** LF：换行（受 origin/scroll region 影响）。 */
    fun lineFeed() {
        if (cursorRow == scrollBottom) {
            scrollUp(1)
        } else {
            cursorRow++
        }
        pendingWrap = false
        markChanged()
    }

    /** CR：回车。 */
    fun carriageReturn() {
        cursorCol = 0
        pendingWrap = false
        markChanged()
    }

    /** 换行（LF + 自动换行处理）。 */
    fun newline() {
        lineFeed()
        carriageReturn()
    }

    fun backspace() {
        if (pendingWrap) pendingWrap = false
        else if (cursorCol > 0) cursorCol--
        markChanged()
    }

    fun tab() {
        var i = cursorCol + 1
        while (i < cols && !tabStops[i]) i++
        cursorCol = minOf(i, cols - 1)
        pendingWrap = false
        markChanged()
    }

    fun moveTo(row: Int, col: Int) {
        val r = if (originMode) row + scrollTop else row
        cursorRow = r.coerceIn(0, rows - 1)
        cursorCol = col.coerceIn(0, cols - 1)
        pendingWrap = false
        markChanged()
    }

    fun moveCursor(dRow: Int, dCol: Int) {
        cursorRow = (cursorRow + dRow).coerceIn(0, rows - 1)
        cursorCol = (cursorCol + dCol).coerceIn(0, cols - 1)
        pendingWrap = false
        markChanged()
    }

    fun saveCursor() {
        savedCursorRow = cursorRow
        savedCursorCol = cursorCol
    }

    fun restoreCursor() {
        cursorRow = savedCursorRow.coerceIn(0, rows - 1)
        cursorCol = savedCursorCol.coerceIn(0, cols - 1)
        pendingWrap = false
        markChanged()
    }

    // ---------- 擦除 ----------

    fun eraseToEndOfLine() {
        val line = lineAt(cursorRow)
        for (i in cursorCol until cols) line.cells[i].clear()
        markChanged()
    }

    fun eraseFromStartOfLine() {
        val line = lineAt(cursorRow)
        for (i in 0..cursorCol) line.cells[i].clear()
        markChanged()
    }

    fun eraseLine() {
        lineAt(cursorRow).clear()
        markChanged()
    }

    fun eraseToEndOfScreen() {
        eraseToEndOfLine()
        for (r in cursorRow + 1 until rows) lineAt(r).clear()
        markChanged()
    }

    fun eraseFromStartOfScreen() {
        eraseFromStartOfLine()
        for (r in 0 until cursorRow) lineAt(r).clear()
        markChanged()
    }

    fun eraseScreen() {
        for (r in 0 until rows) lineAt(r).clear()
        cursorRow = 0
        cursorCol = 0
        markChanged()
    }

    /** 擦除指定数量的字符（ECH）。 */
    fun eraseChars(n: Int) {
        val line = lineAt(cursorRow)
        for (i in cursorCol until minOf(cursorCol + n, cols)) line.cells[i].clear()
        markChanged()
    }

    // ---------- 插入/删除 ----------

    fun insertCells(n: Int) {
        val line = lineAt(cursorRow)
        val count = minOf(n, cols - cursorCol)
        for (i in (cols - 1) downTo (cursorCol + count)) {
            line.cells[i].copyFrom(line.cells[i - count])
        }
        for (i in cursorCol until cursorCol + count) line.cells[i].clear()
        markChanged()
    }

    fun deleteCells(n: Int) {
        val line = lineAt(cursorRow)
        val count = minOf(n, cols - cursorCol)
        for (i in cursorCol until cols - count) {
            line.cells[i].copyFrom(line.cells[i + count])
        }
        for (i in cols - count until cols) line.cells[i].clear()
        markChanged()
    }

    fun insertLines(n: Int) {
        val count = minOf(n, scrollBottom - cursorRow + 1)
        if (cursorRow < scrollTop || cursorRow > scrollBottom) return
        for (r in scrollBottom downTo cursorRow + count) {
            lineAt(r).cells.forEachIndexed { i, _ ->
                lineAt(r).cells[i].copyFrom(lineAt(r - count).cells[i])
            }
        }
        for (r in cursorRow until cursorRow + count) lineAt(r).clear()
        markChanged()
    }

    fun deleteLines(n: Int) {
        val count = minOf(n, scrollBottom - cursorRow + 1)
        if (cursorRow < scrollTop || cursorRow > scrollBottom) return
        for (r in cursorRow until scrollBottom - count + 1) {
            lineAt(r).cells.forEachIndexed { i, _ ->
                lineAt(r).cells[i].copyFrom(lineAt(r + count).cells[i])
            }
        }
        for (r in scrollBottom - count + 1..scrollBottom) lineAt(r).clear()
        markChanged()
    }

    // ---------- 滚动 ----------

    /** 在滚动区域内上滚 [n] 行。 */
    fun scrollUp(n: Int) {
        if (scrollTop == 0 && scrollBottom == rows - 1 && !altScreen) {
            // 全屏滚动：追加新行（进入滚动回看）
            repeat(n) {
                normal.addLast(TerminalLine(cols))
                if (normal.size > maxScrollbackLines + rows) {
                    normal.removeFirst()
                }
            }
        } else if (altScreen || scrollTop < scrollBottom) {
            val region = scrollBottom - scrollTop + 1
            val count = minOf(n, region)
            for (r in scrollTop until scrollTop + region - count) {
                copyLine(r + count, r)
            }
            for (r in scrollTop + region - count until scrollBottom + 1) {
                lineAt(r).clear()
            }
        }
        markChanged()
    }

    fun scrollDown(n: Int) {
        if (scrollTop == 0 && scrollBottom == rows - 1 && !altScreen) {
            // 全屏向下滚动（回看内容回到屏幕）
            repeat(n) {
                if (normal.size > rows) {
                    normal.removeLast()
                    normal.addFirst(TerminalLine(cols))
                }
            }
        } else {
            val region = scrollBottom - scrollTop + 1
            val count = minOf(n, region)
            for (r in (scrollBottom) downTo scrollTop + count) {
                copyLine(r - count, r)
            }
            for (r in scrollTop until scrollTop + count) {
                lineAt(r).clear()
            }
        }
        markChanged()
    }

    private fun copyLine(from: Int, to: Int) {
        val src = lineAt(from)
        val dst = lineAt(to)
        for (i in 0 until cols) dst.cells[i].copyFrom(src.cells[i])
        dst.wrapped = src.wrapped
    }

    // ---------- 模式切换 ----------

    fun enterAltScreen() {
        if (altScreen) return
        altScreen = true
        cursorRow = 0
        cursorCol = 0
        savedCursorRow = 0
        savedCursorCol = 0
        scrollTop = 0
        scrollBottom = rows - 1
        markChanged()
    }

    fun leaveAltScreen() {
        if (!altScreen) return
        altScreen = false
        cursorRow = 0
        cursorCol = 0
        markChanged()
    }

    fun setScrollRegion(top: Int, bottom: Int) {
        if (top >= bottom) return
        scrollTop = top.coerceIn(0, rows - 1)
        scrollBottom = bottom.coerceIn(0, rows - 1)
        cursorRow = 0
        cursorCol = 0
        markChanged()
    }

    fun resetTabStops() {
        tabStops = defaultTabStops(cols)
        markChanged()
    }

    fun setTabStop(col: Int) {
        if (col in 0 until cols) tabStops[col] = true
        markChanged()
    }

    fun clearTabStop(col: Int) {
        if (col in 0 until cols) tabStops[col] = false
        markChanged()
    }

    // ---------- 尺寸调整 ----------

    fun resize(newCols: Int, newRows: Int) {
        if (newCols == cols && newRows == rows) return

        // 调整普通屏每行宽度
        for (line in normal) resizeLine(line, newCols)
        // 调整备用屏
        val newAlt = Array(newRows) { TerminalLine(newCols) }
        for (r in 0 until minOf(rows, newRows)) {
            resizeLine(alt[r], newCols)
            copyInto(newAlt[r], alt[r], newCols)
        }
        alt = newAlt

        // 调整行数
        if (newRows < rows) {
            // 屏幕变矮（如键盘弹出）：优先丢弃光标下方的空白行，保证光标与
            // 提示符仍留在可视区（xterm 行为）；仍不够的收缩量由
            // “可见屏幕 = normal 末尾 newRows 行”自然把顶部行挤入回看。
            val cursorAbs = normal.size - rows + cursorRow
            var toDrop = rows - newRows
            while (toDrop > 0 && normal.size - 1 > cursorAbs && normal.last().isBlank()) {
                normal.removeLast()
                toDrop--
            }
            cursorRow = (cursorAbs - (normal.size - newRows)).coerceIn(0, newRows - 1)
        } else if (newRows > rows) {
            // 屏幕变大：从回看中取出或新建空行
            repeat(newRows - rows) {
                normal.addLast(TerminalLine(newCols))
            }
        }
        // 限制回看总量
        while (normal.size > maxScrollbackLines + newRows) {
            normal.removeFirst()
        }

        cols = newCols
        rows = newRows
        scrollTop = 0
        scrollBottom = rows - 1
        cursorRow = cursorRow.coerceIn(0, rows - 1)
        cursorCol = cursorCol.coerceIn(0, cols - 1)
        savedCursorRow = savedCursorRow.coerceIn(0, rows - 1)
        savedCursorCol = savedCursorCol.coerceIn(0, cols - 1)
        tabStops = defaultTabStops(cols)
        markChanged()
    }

    private fun resizeLine(line: TerminalLine, newCols: Int) {
        if (line.cells.size == newCols) return
        val newCells = Array(newCols) { TerminalCell() }
        val n = minOf(line.cells.size, newCols)
        for (i in 0 until n) {
            newCells[i].copyFrom(line.cells[i])
        }
        // 修正：若截断导致宽字符尾巴悬空，清掉
        line.cells = newCells
    }

    private fun copyInto(dst: TerminalLine, src: TerminalLine, n: Int) {
        for (i in 0 until n) dst.cells[i].copyFrom(src.cells[i])
    }

    companion object {
        private fun defaultTabStops(cols: Int): BooleanArray {
            val arr = BooleanArray(cols)
            var i = 8
            while (i < cols) {
                arr[i] = true
                i += 8
            }
            return arr
        }
    }
}

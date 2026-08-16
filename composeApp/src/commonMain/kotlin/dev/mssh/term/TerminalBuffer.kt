package dev.mssh.term

import kotlin.concurrent.Volatile

/** 单个字符单元。 */
class TerminalCell {
    var codePoint: Int = ' '.code
    var attrs: Int = 0
    var fg: Int = DEFAULT_FG
    var bg: Int = DEFAULT_BG

    /** 该单元是前一个宽字符（占 2 列）的“尾巴”。 */
    var isWideTail: Boolean = false

    /** 该单元占用的列数（1=窄，2=宽字符头部），写入时预计算，渲染不再调 wcwidth。 */
    var width: Int = 1

    /** OSC 8 超链接目标；非超链接单元为 null。 */
    var link: String? = null

    val isWide: Boolean get() = width == 2

    fun clear() {
        codePoint = ' '.code
        attrs = 0
        fg = DEFAULT_FG
        bg = DEFAULT_BG
        isWideTail = false
        width = 1
        link = null
    }

    fun copyFrom(o: TerminalCell) {
        codePoint = o.codePoint
        attrs = o.attrs
        fg = o.fg
        bg = o.bg
        isWideTail = o.isWideTail
        width = o.width
        link = o.link
    }
}

/** 一行字符单元。 */
class TerminalLine(val cols: Int) {
    var cells = Array(cols) { TerminalCell() }

    /** 该行是否因自动换行而开始（用于选择 / 回看等）。 */
    var wrapped: Boolean = false

    /** 行内容版本号：单元格实际被改写时递增，供渲染层做行级缓存失效。 */
    @Volatile
    var version: Long = 0
        internal set

    /** 逻辑行身份：克隆/COW 复制时保留，跨缓冲比较"同一行是否变过"用。 */
    internal var identity: Any = Any()

    /** COW 共享标记：为 true 的行不可原地修改，写前必须克隆（mosh shared_ptr 行语义）。 */
    internal var shared: Boolean = false

    fun touch() {
        version++
    }

    fun clear() {
        wrapped = false
        for (c in cells) c.clear()
        touch()
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
    /** DECSC 一并保存的延迟换行标记与当前字符集（tmux 等依赖完整状态恢复）。 */
    var savedPendingWrap: Boolean = false
    var savedCharset: Charset = Charset.ASCII

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
    /** 默认光标色（RGB），用于应答 OSC 12 查询；OSC 12 设置后覆盖。 */
    var defaultCursorRgb: Int = 0x58a6ff
    var applicationKeypad: Boolean = false

    /** DECSCUSR 光标样式：0=默认闪烁块，1=闪烁块，2=稳态块，3=闪烁下划线，4=稳态下划线，5=闪烁竖线，6=稳态竖线。 */
    var cursorStyle: Int = 0
    /** OSC 12 设置的光标颜色；DEFAULT_CURSOR 表示用主题默认色。 */
    var cursorColor: Int = DEFAULT_CURSOR

    /** DECSET 1004：聚焦/失焦事件（CSI I / CSI O）上报。 */
    var focusEvents: Boolean = false
    /** DECSET 1007：备用屏下滚轮转方向键（alternate scroll）。 */
    var alternateScroll: Boolean = false
    /** DECSET 1015：urxvt 鼠标坐标格式（与 SGR 1006 互斥）。 */
    var mouseUrxvt: Boolean = false

    /** OSC 8 当前超链接目标；OSC 8;; 结束链接时置 null。 */
    var currentLink: String? = null

    var tabStops: BooleanArray = defaultTabStops(cols)
    /** 最近写入的图形字符，供 CSI Ps b（REP）重复。 */
    var lastPrintedCodePoint: Int = 0

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

    /** 写访问：行被 COW 共享时先克隆，保证共享行（影子历史/UI 引用）永不被原地修改。 */
    private fun mutableLine(row: Int): TerminalLine {
        val line = lineAt(row)
        if (!line.shared) return line
        val clone = cloneLine(line)
        if (altScreen) alt[row] = clone else normal[lineIndex(row)] = clone
        return clone
    }

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
        // 上个字符落在行末（pendingWrap）：先处理延迟换行
        if (pendingWrap) {
            pendingWrap = false
            if (autoWrap) {
                val wrapLine = mutableLine(cursorRow)
                wrapLine.wrapped = true
                wrapLine.touch()
                newline()
            } else {
                // DECAWM 关闭：光标钳在行末，新字符覆盖最后一格（xterm 行为）
                cursorCol = cols - 1
            }
        }
        val width = if (cp == 0) 1 else CharWidth.wcwidth(cp)
        val line = mutableLine(cursorRow)
        val col = cursorCol

        // 落点在宽字符尾巴上：先清掉头，避免残留半个宽字符
        if (col > 0 && line.cells[col].isWideTail) line.cells[col - 1].clear()

        if (width == 2) {
            if (cols < 2) { writeNarrow(cp); return }
            // 宽字符：若处于行末，先换行（DECAWM 关闭时不换行，按窄字符覆盖最后一格）
            if (col >= cols - 1) {
                if (autoWrap) {
                    newline()
                    putChar(cp)
                } else {
                    writeNarrow(cp)
                }
                return
            }
            lastPrintedCodePoint = cp
            val c = line.cells[col]
            c.clear()
            c.codePoint = cp
            c.attrs = currentAttrs
            c.fg = currentFg
            c.bg = currentBg
            c.width = 2
            c.link = currentLink
            val tail = line.cells[col + 1]
            tail.clear()
            // 尾巴必须继承头的颜色/属性：否则宽字符右半格露出默认背景，
            // 在带底色的行（如 pi 的消息条）上表现为黑块盖住半个字
            tail.attrs = currentAttrs
            tail.fg = currentFg
            tail.bg = currentBg
            tail.isWideTail = true
            tail.link = currentLink
            // 尾巴盖掉了原宽字符的头：清掉它原来尾巴的标记，避免越雷悬空
            if (col + 2 < cols && line.cells[col + 2].isWideTail) line.cells[col + 2].isWideTail = false
            cursorCol = col + 2
            line.touch()
            markChanged()
            return
        }

        writeNarrow(cp)
    }

    private fun writeNarrow(cp: Int) {
        if (insertMode) {
            insertCells(1)
        }
        val line = mutableLine(cursorRow)
        // 落点在宽字符尾巴上：先清掉头
        if (cursorCol > 0 && line.cells[cursorCol].isWideTail) line.cells[cursorCol - 1].clear()
        lastPrintedCodePoint = cp
        val c = line.cells[cursorCol]
        c.clear()
        c.codePoint = cp
        c.attrs = currentAttrs
        c.fg = currentFg
        c.bg = currentBg
        c.link = currentLink
        // 覆盖宽字符头时清掉尾巴标记
        if (cursorCol + 1 < cols && line.cells[cursorCol + 1].isWideTail) {
            line.cells[cursorCol + 1].isWideTail = false
        }

        if (cursorCol == cols - 1) {
            // 行末：标记 wrap，延迟到下一个字符再换行（pendingWrap 在 putChar 入口消费）
            pendingWrap = true
        } else {
            cursorCol++
        }
        line.touch()
        markChanged()
    }

    /** CSI Ps b（REP）：在当前光标处重复最近写入的图形字符 Ps 次。 */
    fun repeatChar(n: Int) {
        if (lastPrintedCodePoint == 0) return
        repeat(n.coerceIn(1, 1024)) { putChar(lastPrintedCodePoint) }
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
            // 光标可能位于滚动区域下方（CUP 移过去再收到 LF）：钳在 rows-1，
            // 否则 cursorRow 越界后 lineAt() 直接崩溃
            cursorRow = (cursorRow + 1).coerceAtMost(rows - 1)
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
        savedPendingWrap = pendingWrap
        savedCharset = currentCharset
    }

    fun restoreCursor() {
        cursorRow = savedCursorRow.coerceIn(0, rows - 1)
        cursorCol = savedCursorCol.coerceIn(0, cols - 1)
        pendingWrap = savedPendingWrap
        currentCharset = savedCharset
        markChanged()
    }

    // ---------- 擦除 ----------

    fun eraseToEndOfLine() {
        val line = mutableLine(cursorRow)
        for (i in cursorCol until cols) line.cells[i].clear()
        line.touch()
        markChanged()
    }

    fun eraseFromStartOfLine() {
        val line = mutableLine(cursorRow)
        for (i in 0..cursorCol) line.cells[i].clear()
        line.touch()
        markChanged()
    }

    fun eraseLine() {
        mutableLine(cursorRow).clear()
        markChanged()
    }

    fun eraseToEndOfScreen() {
        eraseToEndOfLine()
        for (r in cursorRow + 1 until rows) mutableLine(r).clear()
        markChanged()
    }

    fun eraseFromStartOfScreen() {
        eraseFromStartOfLine()
        for (r in 0 until cursorRow) mutableLine(r).clear()
        markChanged()
    }

    /** ED 2：清屏。xterm 语义：不移动光标。 */
    fun eraseScreen() {
        for (r in 0 until rows) mutableLine(r).clear()
        markChanged()
    }

    /** ED 3：清屏并清空滚动回看（普通屏）。 */
    fun eraseScreenAndScrollback() {
        eraseScreen()
        if (!altScreen) {
            repeat(normal.size - rows) { normal.removeFirst() }
        }
        markChanged()
    }

    /** 擦除指定数量的字符（ECH）。 */
    fun eraseChars(n: Int) {
        val line = mutableLine(cursorRow)
        for (i in cursorCol until minOf(cursorCol + n, cols)) line.cells[i].clear()
        line.touch()
        markChanged()
    }

    // ---------- 插入/删除 ----------

    fun insertCells(n: Int) {
        val line = mutableLine(cursorRow)
        val count = minOf(n, cols - cursorCol)
        for (i in (cols - 1) downTo (cursorCol + count)) {
            line.cells[i].copyFrom(line.cells[i - count])
        }
        for (i in cursorCol until cursorCol + count) line.cells[i].clear()
        line.touch()
        markChanged()
    }

    fun deleteCells(n: Int) {
        val line = mutableLine(cursorRow)
        val count = minOf(n, cols - cursorCol)
        for (i in cursorCol until cols - count) {
            line.cells[i].copyFrom(line.cells[i + count])
        }
        for (i in cols - count until cols) line.cells[i].clear()
        line.touch()
        markChanged()
    }

    fun insertLines(n: Int) {
        val count = minOf(n, scrollBottom - cursorRow + 1)
        if (cursorRow < scrollTop || cursorRow > scrollBottom) return
        for (r in scrollBottom downTo cursorRow + count) {
            val src = lineAt(r - count) // 只读
            val dst = mutableLine(r) // 写
            for (i in 0 until cols) {
                dst.cells[i].copyFrom(src.cells[i])
            }
            dst.touch()
        }
        for (r in cursorRow until cursorRow + count) mutableLine(r).clear()
        markChanged()
    }

    fun deleteLines(n: Int) {
        val count = minOf(n, scrollBottom - cursorRow + 1)
        if (cursorRow < scrollTop || cursorRow > scrollBottom) return
        for (r in cursorRow until scrollBottom - count + 1) {
            val src = lineAt(r + count) // 只读
            val dst = mutableLine(r) // 写
            for (i in 0 until cols) {
                dst.cells[i].copyFrom(src.cells[i])
            }
            dst.touch()
        }
        for (r in scrollBottom - count + 1..scrollBottom) mutableLine(r).clear()
        markChanged()
    }

    // ---------- 滚动 ----------

    /** 在滚动区域内上滚 [n] 行。 */
    fun scrollUp(n: Int) {
        if (scrollTop == 0 && scrollBottom == rows - 1 && !altScreen) {
            // 全屏滚动：追加新行（进入滚动回看）
            repeat(n) {
                normal.addLast(TerminalLine(cols))
                if (maxScrollbackLines != Int.MAX_VALUE && normal.size > maxScrollbackLines + rows) {
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
                mutableLine(r).clear()
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
                mutableLine(r).clear()
            }
        }
        markChanged()
    }

    private fun copyLine(from: Int, to: Int) {
        val src = lineAt(from)
        val dst = mutableLine(to)
        for (i in 0 until cols) dst.cells[i].copyFrom(src.cells[i])
        dst.wrapped = src.wrapped
        dst.touch()
    }

    // ---------- 模式切换 ----------

    fun enterAltScreen(clear: Boolean = false) {
        if (altScreen) return
        altScreen = true
        // 1049 进入备用屏应清屏（xterm 行为）；1047/47 不清。
        // 不清的话上次 vim/tmux 的残留会在再次进入时闪一帧。
        if (clear) for (r in 0 until rows) mutableLine(r).clear()
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

        // 调整普通屏每行宽度（COW：共享行先克隆再改）
        for (i in normal.indices) {
            val line = normal[i]
            if (line.shared) normal[i] = cloneLine(line)
            resizeLine(normal[i], newCols)
        }
        // 调整备用屏
        val newAlt = Array(newRows) { TerminalLine(newCols) }
        for (r in 0 until minOf(rows, newRows)) {
            val src = alt[r]
            val srcMutable = if (src.shared) cloneLine(src) else src
            resizeLine(srcMutable, newCols)
            copyInto(newAlt[r], srcMutable, newCols)
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
        // 限制回看总量（Int.MAX_VALUE = 无上限，影子终端用；普通 UI 缓冲保留上限）
        while (maxScrollbackLines != Int.MAX_VALUE && normal.size > maxScrollbackLines + newRows) {
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

    // ---------- 整体复制（mosh KMP 影子状态 / UI 同步用） ----------

    /** COW 浅分叉：行对象与源共享（写时复制），O(行数) 而非 O(单元格)。
     *  供 mosh 影子状态保存（对应协议 时间戳状态 的 shared_ptr 行拷贝）。 */
    fun shallowFork(): TerminalBuffer {
        val dst = TerminalBuffer(cols, rows, maxScrollbackLines)
        dst.cols = cols
        dst.rows = rows
        for (line in normal) {
            line.shared = true
            dst.normal.addLast(line)
        }
        dst.alt = Array(rows) { idx ->
            alt[idx].also { it.shared = true }
        }
        dst.copyStateFieldsFrom(this)
        return dst
    }

    /** 深拷贝：用于 mosh SSP 的影子终端状态分叉保存。 */
    fun deepCopy(): TerminalBuffer {
        val dst = TerminalBuffer(cols, rows, maxScrollbackLines)
        dst.copyContentFrom(this)
        return dst
    }

    /** 用 other 的显示状态替换本缓冲（保持实例不变，供 UI 渲染引用）。
     *  行级增量：同一逻辑行（identity 相同）且版本未变则复用目标行，只克隆变化行，
     *  避免大回看下每帧全量克隆单元格。 */
    fun copyContentFrom(other: TerminalBuffer) {
        val oldCols = cols
        cols = other.cols
        rows = other.rows
        // 目标缓冲设了上限时只拷贝保留段（最后 maxScrollback+rows 行），
        // 避免影子无上限后每帧先克隆全部再丢弃（影子 Int.MAX_VALUE 则全量拷贝）
        val keep = if (maxScrollbackLines == Int.MAX_VALUE) {
            other.normal.size
        } else {
            minOf(other.normal.size, maxScrollbackLines + rows)
        }
        val offset = other.normal.size - keep
        val shapeStable = oldCols == other.cols && normal.size == keep
        if (!shapeStable) normal.clear()
        var dstIdx = 0
        for (j in offset until other.normal.size) {
            val src = other.normal[j]
            if (shapeStable) {
                val dst = normal[dstIdx]
                if (dst.identity !== src.identity || dst.version != src.version) {
                    normal[dstIdx] = cloneLine(src)
                } else {
                    dst.wrapped = src.wrapped
                }
            } else {
                normal.addLast(cloneLine(src))
            }
            dstIdx++
        }
        alt = Array(other.rows) { cloneLine(other.alt[it]) }
        copyStateFieldsFrom(other)
        markChanged()
    }

    private fun copyStateFieldsFrom(other: TerminalBuffer) {
        altScreen = other.altScreen
        cursorRow = other.cursorRow
        cursorCol = other.cursorCol
        cursorVisible = other.cursorVisible
        savedCursorRow = other.savedCursorRow
        savedCursorCol = other.savedCursorCol
        savedPendingWrap = other.savedPendingWrap
        savedCharset = other.savedCharset
        scrollTop = other.scrollTop
        scrollBottom = other.scrollBottom
        autoWrap = other.autoWrap
        originMode = other.originMode
        insertMode = other.insertMode
        applicationCursorKeys = other.applicationCursorKeys
        bracketedPaste = other.bracketedPaste
        mouseTracking = other.mouseTracking
        mouseSgr = other.mouseSgr
        defaultFgRgb = other.defaultFgRgb
        defaultBgRgb = other.defaultBgRgb
        defaultCursorRgb = other.defaultCursorRgb
        applicationKeypad = other.applicationKeypad
        cursorStyle = other.cursorStyle
        cursorColor = other.cursorColor
        focusEvents = other.focusEvents
        alternateScroll = other.alternateScroll
        mouseUrxvt = other.mouseUrxvt
        currentLink = other.currentLink
        tabStops = other.tabStops.copyOf()
        lastPrintedCodePoint = other.lastPrintedCodePoint
        currentCharset = other.currentCharset
        pendingWrap = other.pendingWrap
    }

    private fun cloneLine(src: TerminalLine): TerminalLine {
        val dst = TerminalLine(src.cols)
        for (i in 0 until src.cols) dst.cells[i].copyFrom(src.cells[i])
        dst.wrapped = src.wrapped
        // 保留逻辑身份与内容版本：跨缓冲行级增量比较依赖它
        dst.identity = src.identity
        dst.version = src.version
        dst.shared = false
        return dst
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
        line.touch()
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

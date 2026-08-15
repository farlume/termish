package dev.mssh.term

/**
 * VT100/xterm 终端模拟器：UTF-8 解码 + 转义序列状态机 + 屏幕执行。
 *
 * 纯 Kotlin，无平台依赖，可单元测试。覆盖 shell 日常使用与 vim/tmux/htop 等
 * 全屏应用所需的绝大多数序列（C0、ANSI CSI、SGR 颜色/真彩、OSC 标题、备用屏、
 * 滚动区域、插入/删除、DEC 特殊图形字符集、光标保存/恢复、模式切换）。
 */
class TerminalEmulator(
    private val buffer: TerminalBuffer,
    var onTitleChange: (String) -> Unit = {},
    /** 终端需要向远端回写数据（如 DSR 应答）时调用。 */
    var onResponse: (ByteArray) -> Unit = {},
) {

    private enum class State { GROUND, ESCAPE, CSI_ENTRY, CSI_PARAM, CSI_INTERMEDIATE, OSC, OSC_ESC, DCS, DCS_ESC, ESC_INTERMEDIATE }

    private var state = State.GROUND
    private val params = StringBuilder()
    private val intermediates = StringBuilder()
    private val oscBuf = StringBuilder()
    private val dcsBuf = StringBuilder()
    private var oscTermByBel = false

    // UTF-8 增量解码
    private var utf8Expected = 0
    private var utf8Accum = 0
    private var utf8Remaining = 0

    private var g0Charset = TerminalBuffer.Charset.ASCII
    private var g1Charset = TerminalBuffer.Charset.ASCII
    private var shiftedOut = false // SO 激活 G1

    fun write(bytes: ByteArray) {
        var i = 0
        while (i < bytes.size) {
            val cp = decodeUtf8(bytes[i].toInt() and 0xff)
            i++
            if (cp < 0) continue // 等待更多字节
            feedCodePoint(cp)
        }
    }

    fun writeText(text: String) {
        // 直接按码点喂入，避免再次编码/解码（UTF-16 代理对手动解码）
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val cp = if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
                val hi = c.code
                val lo = text[i + 1].code
                i++
                0x10000 + ((hi - 0xD800) shl 10) + (lo - 0xDC00)
            } else {
                c.code
            }
            feedCodePoint(cp)
            i++
        }
    }

    // ---------- UTF-8 解码 ----------

    /** 返回码点，或 -1 表示需要更多字节，或 0xFFFD 表示非法。 */
    private fun decodeUtf8(b: Int): Int {
        if (utf8Expected == 0) {
            return when {
                b < 0x80 -> b
                b in 0xC2..0xDF -> startUtf8(b, 2, 0x1F)
                b in 0xE0..0xEF -> startUtf8(b, 3, 0x0F)
                b in 0xF0..0xF4 -> startUtf8(b, 4, 0x07)
                else -> 0xFFFD // 非法前导字节
            }
        }
        // 期望续字节
        if (b in 0x80..0xBF) {
            utf8Accum = (utf8Accum shl 6) or (b and 0x3F)
            utf8Remaining--
            if (utf8Remaining == 0) {
                utf8Expected = 0
                return utf8Accum
            }
            return -1
        }
        // 非法续字节：丢弃当前序列，把该字节当新的前导字节
        utf8Expected = 0
        return decodeUtf8(b)
    }

    private fun startUtf8(b: Int, len: Int, mask: Int): Int {
        utf8Expected = len
        utf8Remaining = len - 1
        utf8Accum = b and mask
        return -1
    }

    // ---------- 状态机 ----------

    private fun feedCodePoint(cp: Int) {
        when (state) {
            State.GROUND -> ground(cp)
            State.ESCAPE -> escape(cp)
            State.ESC_INTERMEDIATE -> escapeIntermediate(cp)
            State.CSI_ENTRY -> csiEntry(cp)
            State.CSI_PARAM -> csiParam(cp)
            State.CSI_INTERMEDIATE -> csiIntermediate(cp)
            State.OSC -> osc(cp)
            State.OSC_ESC -> oscEsc(cp)
            State.DCS -> dcs(cp)
            State.DCS_ESC -> dcsEsc(cp)
        }
    }

    private fun ground(cp: Int) {
        when (cp) {
            0x00, 0x07 -> {} // NUL / BEL
            0x08 -> buffer.backspace()
            0x09 -> buffer.tab()
            0x0A, 0x0B, 0x0C -> buffer.lineFeed()
            0x0D -> buffer.carriageReturn()
            0x0E -> { shiftedOut = true; buffer.currentCharset = g1Charset }
            0x0F -> { shiftedOut = false; buffer.currentCharset = g0Charset }
            0x1B -> state = State.ESCAPE
            else -> {
                if (cp < 0x20) return // 其它 C0 忽略
                val out = if (buffer.currentCharset == TerminalBuffer.Charset.DEC_SPECIAL) mapDecSpecial(cp) else cp
                buffer.putChar(out)
            }
        }
    }

    private fun escape(cp: Int) {
        when (cp) {
            '['.code -> { state = State.CSI_ENTRY; params.clear(); intermediates.clear() }
            ']'.code -> { state = State.OSC; oscBuf.clear() }
            'P'.code -> { state = State.DCS; dcsBuf.clear() }
            '7'.code -> buffer.saveCursor().also { state = State.GROUND }
            '8'.code -> buffer.restoreCursor().also { state = State.GROUND }
            'D'.code -> buffer.lineFeed().also { state = State.GROUND }
            'E'.code -> buffer.newline().also { state = State.GROUND }
            'M'.code -> reverseIndex().also { state = State.GROUND }
            'H'.code -> buffer.setTabStop(buffer.cursorCol).also { state = State.GROUND }
            'c'.code -> fullReset().also { state = State.GROUND }
            '='.code -> { buffer.applicationKeypad = true; state = State.GROUND }
            '>'.code -> { buffer.applicationKeypad = false; state = State.GROUND }
            '('.code -> { state = State.ESC_INTERMEDIATE; intermediates.clear(); intermediates.append('(') }
            ')'.code -> { state = State.ESC_INTERMEDIATE; intermediates.clear(); intermediates.append(')') }
            '#'.code -> { state = State.ESC_INTERMEDIATE; intermediates.clear(); intermediates.append('#') }
            else -> state = State.GROUND
        }
    }

    private fun escapeIntermediate(cp: Int) {
        when (intermediates.toString()) {
            "(" -> { g0Charset = charsetFor(cp); buffer.currentCharset = if (!shiftedOut) g0Charset else g1Charset }
            ")" -> { g1Charset = charsetFor(cp); buffer.currentCharset = if (shiftedOut) g1Charset else g0Charset }
            "#" -> if (cp == '8'.code) alignmentTest()
        }
        state = State.GROUND
    }

    private fun csiEntry(cp: Int) {
        when {
            cp in 0x30..0x3F -> { params.append(cp.toChar()); state = State.CSI_PARAM }
            cp in 0x20..0x2F -> { intermediates.append(cp.toChar()); state = State.CSI_INTERMEDIATE }
            cp in 0x40..0x7E -> { executeCsi(cp); state = State.GROUND }
            else -> state = State.GROUND
        }
    }

    private fun csiParam(cp: Int) {
        when {
            cp in 0x30..0x3F -> params.append(cp.toChar())
            cp in 0x20..0x2F -> { intermediates.append(cp.toChar()); state = State.CSI_INTERMEDIATE }
            cp in 0x40..0x7E -> { executeCsi(cp); state = State.GROUND }
            else -> state = State.GROUND
        }
    }

    private fun csiIntermediate(cp: Int) {
        when {
            cp in 0x20..0x2F -> intermediates.append(cp.toChar())
            cp in 0x40..0x7E -> { executeCsi(cp); state = State.GROUND }
            else -> state = State.GROUND
        }
    }

    private fun osc(cp: Int) {
        when {
            cp == 0x07 -> { oscTermByBel = true; finishOsc(); state = State.GROUND }
            cp == 0x1B -> state = State.OSC_ESC
            else -> oscBuf.append(codePointToString(cp))
        }
    }

    private fun oscEsc(cp: Int) {
        if (cp == '\\'.code) { finishOsc(); state = State.GROUND } else state = State.GROUND
    }

    private fun dcs(cp: Int) {
        when {
            cp == 0x1B -> state = State.DCS_ESC
            else -> {} // 忽略 DCS 内容（如 tmux passthrough）
        }
    }

    private fun dcsEsc(cp: Int) {
        if (cp == '\\'.code) state = State.GROUND else state = State.DCS
    }

    // ---------- CSI 执行 ----------

    private fun executeCsi(finalByte: Int) {
        val isPrivate = params.isNotEmpty() && params[0] in setOf('?', '<', '=', '>')
        val p = parseParams(params.toString(), isPrivate)

        when (finalByte) {
            'A'.code -> buffer.moveCursor(-(p.getOrNull(0) ?: 1), 0)
            'B'.code -> buffer.moveCursor(p.getOrNull(0) ?: 1, 0)
            'C'.code -> buffer.moveCursor(0, p.getOrNull(0) ?: 1)
            'D'.code -> buffer.moveCursor(0, -(p.getOrNull(0) ?: 1))
            'E'.code -> { buffer.moveCursor(p.getOrNull(0) ?: 1, 0); buffer.carriageReturn() }
            'F'.code -> { buffer.moveCursor(-(p.getOrNull(0) ?: 1), 0); buffer.carriageReturn() }
            'G'.code -> buffer.moveTo(buffer.cursorRow, (p.getOrNull(0) ?: 1) - 1)
            'H'.code, 'f'.code -> buffer.moveTo((p.getOrNull(0) ?: 1) - 1, (p.getOrNull(1) ?: 1) - 1)
            'd'.code -> buffer.moveTo((p.getOrNull(0) ?: 1) - 1, buffer.cursorCol)
            'J'.code -> eraseDisplay(p.getOrNull(0) ?: 0)
            'K'.code -> eraseLine(p.getOrNull(0) ?: 0)
            'L'.code -> buffer.insertLines(p.getOrNull(0) ?: 1)
            'M'.code -> buffer.deleteLines(p.getOrNull(0) ?: 1)
            'P'.code -> buffer.deleteCells(p.getOrNull(0) ?: 1)
            '@'.code -> buffer.insertCells(p.getOrNull(0) ?: 1)
            'X'.code -> buffer.eraseChars(p.getOrNull(0) ?: 1)
            'S'.code -> buffer.scrollUp(p.getOrNull(0) ?: 1)
            'T'.code -> buffer.scrollDown(p.getOrNull(0) ?: 1)
            'r'.code -> buffer.setScrollRegion(
                (p.getOrNull(0) ?: 1) - 1,
                (p.getOrNull(1) ?: buffer.rows) - 1
            )
            's'.code -> buffer.saveCursor()
            'u'.code -> buffer.restoreCursor()
            'm'.code -> applySgr(p)
            'h'.code -> setModes(p, true, isPrivate)
            'l'.code -> setModes(p, false, isPrivate)
            'n'.code -> respondToDsr(p.getOrNull(0) ?: 0, isPrivate)
            'c'.code -> if (!isPrivate) onResponse("\u001b[?1;2c".encodeToByteArray())
            else -> {}
        }
    }

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> buffer.eraseToEndOfScreen()
            1 -> buffer.eraseFromStartOfScreen()
            2, 3 -> buffer.eraseScreen()
        }
    }

    private fun eraseLine(mode: Int) {
        when (mode) {
            0 -> buffer.eraseToEndOfLine()
            1 -> buffer.eraseFromStartOfLine()
            2 -> buffer.eraseLine()
        }
    }

    // ---------- SGR ----------

    private fun applySgr(params: List<Int>) {
        if (params.isEmpty()) {
            resetSgr()
            return
        }
        var i = 0
        while (i < params.size) {
            val n = params[i]
            when {
                n == 0 -> resetSgr()
                n == 1 -> buffer.currentAttrs = buffer.currentAttrs or CellAttr.BOLD
                n == 2 -> buffer.currentAttrs = buffer.currentAttrs or CellAttr.DIM
                n == 3 -> buffer.currentAttrs = buffer.currentAttrs or CellAttr.ITALIC
                n == 4 -> buffer.currentAttrs = buffer.currentAttrs or CellAttr.UNDERLINE
                n == 5 -> buffer.currentAttrs = buffer.currentAttrs or CellAttr.BLINK
                n == 7 -> buffer.currentAttrs = buffer.currentAttrs or CellAttr.INVERSE
                n == 8 -> buffer.currentAttrs = buffer.currentAttrs or CellAttr.HIDDEN
                n == 9 -> buffer.currentAttrs = buffer.currentAttrs or CellAttr.STRIKE
                n == 21 -> buffer.currentAttrs = buffer.currentAttrs or CellAttr.UNDERLINE
                n == 22 -> buffer.currentAttrs = buffer.currentAttrs and (CellAttr.BOLD or CellAttr.DIM).inv()
                n == 23 -> buffer.currentAttrs = buffer.currentAttrs and CellAttr.ITALIC.inv()
                n == 24 -> buffer.currentAttrs = buffer.currentAttrs and CellAttr.UNDERLINE.inv()
                n == 25 -> buffer.currentAttrs = buffer.currentAttrs and CellAttr.BLINK.inv()
                n == 27 -> buffer.currentAttrs = buffer.currentAttrs and CellAttr.INVERSE.inv()
                n == 28 -> buffer.currentAttrs = buffer.currentAttrs and CellAttr.HIDDEN.inv()
                n == 29 -> buffer.currentAttrs = buffer.currentAttrs and CellAttr.STRIKE.inv()
                n in 30..37 -> buffer.currentFg = TerminalPalette.BASIC_16[n - 30]
                n == 38 -> { i += applyExtendedColor(params, i, fg = true); }
                n == 39 -> buffer.currentFg = DEFAULT_FG
                n in 40..47 -> buffer.currentBg = TerminalPalette.BASIC_16[n - 40]
                n == 48 -> { i += applyExtendedColor(params, i, fg = false); }
                n == 49 -> buffer.currentBg = DEFAULT_BG
                n in 90..97 -> buffer.currentFg = TerminalPalette.BASIC_16[n - 90 + 8]
                n in 100..107 -> buffer.currentBg = TerminalPalette.BASIC_16[n - 100 + 8]
            }
            i++
        }
    }

    /** 处理 38;5;n / 38;2;r;g;b / 48;... 返回消耗掉的额外参数个数。 */
    private fun applyExtendedColor(params: List<Int>, idx: Int, fg: Boolean): Int {
        if (idx + 1 >= params.size) return 0
        when (params[idx + 1]) {
            5 -> {
                if (idx + 2 < params.size) {
                    val c = TerminalPalette.PALETTE_256.getOrElse(params[idx + 2]) { 0 }
                    if (fg) buffer.currentFg = c else buffer.currentBg = c
                    return 2
                }
            }
            2 -> {
                if (idx + 4 < params.size) {
                    val c = TerminalPalette.rgb(params[idx + 2], params[idx + 3], params[idx + 4])
                    if (fg) buffer.currentFg = c else buffer.currentBg = c
                    return 4
                }
            }
        }
        return 0
    }

    private fun resetSgr() {
        buffer.currentAttrs = 0
        buffer.currentFg = DEFAULT_FG
        buffer.currentBg = DEFAULT_BG
    }

    // ---------- 模式 ----------

    private fun setModes(params: List<Int>, set: Boolean, private: Boolean) {
        for (p in params) {
            when {
                !private && p == 4 -> buffer.insertMode = set
                !private && p == 20 -> {} // LF→CRLF，忽略
                private && p == 1 -> buffer.applicationCursorKeys = set
                private && p == 6 -> buffer.originMode = set
                private && p == 7 -> buffer.autoWrap = set
                private && p == 25 -> buffer.cursorVisible = set
                private && p == 47 -> if (set) buffer.enterAltScreen() else buffer.leaveAltScreen()
                private && p == 1047 -> if (set) buffer.enterAltScreen() else buffer.leaveAltScreen()
                private && p == 1048 -> if (set) buffer.saveCursor() else buffer.restoreCursor()
                private && p == 1049 -> {
                    if (set) { buffer.saveCursor(); buffer.enterAltScreen() }
                    else { buffer.leaveAltScreen(); buffer.restoreCursor() }
                }
                private && p == 2004 -> {} // bracketed paste，跟踪但不实现
            }
        }
    }

    private fun parseParams(raw: String, isPrivate: Boolean): List<Int> {
        // 去掉私有前缀字符
        var s = raw
        while (s.isNotEmpty() && s[0] in setOf('?', '<', '=', '>')) s = s.substring(1)
        if (s.isEmpty()) return emptyList()
        return s.split(';').map { part ->
            part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        }
    }

    // ---------- 其它 ----------

    private fun reverseIndex() {
        if (buffer.cursorRow == buffer.scrollTop) buffer.scrollDown(1)
        else buffer.cursorRow = (buffer.cursorRow - 1).coerceAtLeast(0)
    }

    private fun fullReset() {
        buffer.currentAttrs = 0
        buffer.currentFg = DEFAULT_FG
        buffer.currentBg = DEFAULT_BG
        buffer.insertMode = false
        buffer.autoWrap = true
        buffer.originMode = false
        buffer.applicationCursorKeys = false
        buffer.applicationKeypad = false
        buffer.cursorVisible = true
        buffer.setScrollRegion(0, buffer.rows - 1)
        buffer.resetTabStops()
        g0Charset = TerminalBuffer.Charset.ASCII
        g1Charset = TerminalBuffer.Charset.ASCII
        shiftedOut = false
        buffer.currentCharset = TerminalBuffer.Charset.ASCII
    }

    private fun alignmentTest() {
        for (r in 0 until buffer.rows) {
            val line = buffer.lineAt(r)
            for (c in line.cells) {
                c.clear()
                c.codePoint = 'E'.code
            }
        }
    }

    private fun respondToDsr(arg: Int, isPrivate: Boolean) {
        val resp = when {
            isPrivate && arg == 6 -> "\u001b[${buffer.cursorRow + 1};${buffer.cursorCol + 1}R"
            !isPrivate && arg == 6 -> "\u001b[${buffer.cursorRow + 1};${buffer.cursorCol + 1}R"
            arg == 5 -> "\u001b[0n"
            else -> return
        }
        onResponse(resp.encodeToByteArray())
    }

    private fun finishOsc() {
        val content = oscBuf.toString()
        oscBuf.clear()
        // OSC Ps ; Pt
        val semi = content.indexOf(';')
        val ps = if (semi >= 0) content.substring(0, semi).trim().toIntOrNull() ?: 0 else 0
        val pt = if (semi >= 0) content.substring(semi + 1) else ""
        when (ps) {
            0, 1, 2 -> onTitleChange(pt)
            else -> {} // 忽略其它 OSC（如 4 调色板、8 超链接）
        }
    }

    private fun charsetFor(c: Int): TerminalBuffer.Charset =
        if (c == '0'.code) TerminalBuffer.Charset.DEC_SPECIAL else TerminalBuffer.Charset.ASCII

    private fun mapDecSpecial(cp: Int): Int = when (cp) {
        0x60 -> 0x25C6 // ◆ diamond
        0x61 -> 0x2592 // ▒
        0x62 -> 0x2409 // ␉
        0x63 -> 0x240C // ␌
        0x64 -> 0x240D // ␍
        0x65 -> 0x240A // ␊
        0x66 -> 0x00B0 // °
        0x67 -> 0x00B1 // ±
        0x68 -> 0x2424 // ␤
        0x69 -> 0x240B // ␋
        0x6A -> 0x2518 // ┘
        0x6B -> 0x2510 // ┐
        0x6C -> 0x250C // ┌
        0x6D -> 0x2514 // └
        0x6E -> 0x253C // ┼
        0x6F -> 0x23BA // ⎺
        0x70 -> 0x23BB // ⎻
        0x71 -> 0x2500 // ─
        0x72 -> 0x23BC // ⎼
        0x73 -> 0x23BD // ⎽
        0x74 -> 0x251C // ├
        0x75 -> 0x2524 // ┤
        0x76 -> 0x2534 // ┴
        0x77 -> 0x252C // ┬
        0x78 -> 0x2502 // │
        0x79 -> 0x2264 // ≤
        0x7A -> 0x2265 // ≥
        0x7B -> 0x03C0 // π
        0x7C -> 0x2260 // ≠
        0x7D -> 0x00A3 // £
        0x7E -> 0x00B7 // ·
        else -> cp
    }

    private fun codePointToString(cp: Int): String = when {
        cp <= 0xFFFF -> cp.toChar().toString()
        else -> {
            val x = cp - 0x10000
            val hi = 0xD800 + (x shr 10)
            val lo = 0xDC00 + (x and 0x3FF)
            "${hi.toChar()}${lo.toChar()}"
        }
    }

    fun reset() {
        fullReset()
        buffer.eraseScreen()
    }
}

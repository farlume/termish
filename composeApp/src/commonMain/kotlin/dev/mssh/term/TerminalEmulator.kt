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
    /** OSC 52：远端程序请求写系统剪贴板。 */
    var onClipboardWrite: (String) -> Unit = {},
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
                if (cp < 0x20 || cp == 0x7F) return // 其它 C0 / DEL 忽略
                if (cp in 0x80..0x9F) return // C1 控制字符忽略（不支持，不应当字符画出来）
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
            cp in 0x40..0x7E -> { executeCsi(cp, intermediates.toString()); state = State.GROUND }
            else -> state = State.GROUND
        }
    }

    private fun csiParam(cp: Int) {
        when {
            cp in 0x30..0x3F -> params.append(cp.toChar())
            cp in 0x20..0x2F -> { intermediates.append(cp.toChar()); state = State.CSI_INTERMEDIATE }
            cp in 0x40..0x7E -> { executeCsi(cp, intermediates.toString()); state = State.GROUND }
            else -> state = State.GROUND
        }
    }

    private fun csiIntermediate(cp: Int) {
        when {
            cp in 0x20..0x2F -> intermediates.append(cp.toChar())
            cp in 0x40..0x7E -> { executeCsi(cp, intermediates.toString()); state = State.GROUND }
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
            // 只关心短查询（DECRQSS）；sixel/tmux passthrough 等大块内容忽略且限长防内存膨胀
            else -> if (dcsBuf.length < 65536) dcsBuf.append(codePointToString(cp))
        }
    }

    private fun dcsEsc(cp: Int) {
        if (cp == '\\'.code) {
            finishDcs()
            state = State.GROUND
        } else state = State.DCS
    }

    // ---------- CSI 执行 ----------

    private fun executeCsi(finalByte: Int, intermediates: String) {
        val isPrivate = params.isNotEmpty() && params[0] in setOf('?', '<', '=', '>')
        val p = parseParams(params.toString(), isPrivate)

        when (finalByte) {
            'A'.code -> buffer.moveCursor(-n1(p, 0), 0)
            'B'.code -> buffer.moveCursor(n1(p, 0), 0)
            'C'.code -> buffer.moveCursor(0, n1(p, 0))
            'D'.code -> buffer.moveCursor(0, -n1(p, 0))
            'E'.code -> { buffer.moveCursor(n1(p, 0), 0); buffer.carriageReturn() }
            'F'.code -> { buffer.moveCursor(-n1(p, 0), 0); buffer.carriageReturn() }
            'G'.code -> buffer.moveTo(buffer.cursorRow, (p.getOrNull(0) ?: 1) - 1)
            'H'.code, 'f'.code -> buffer.moveTo((p.getOrNull(0) ?: 1) - 1, (p.getOrNull(1) ?: 1) - 1)
            'd'.code -> buffer.moveTo((p.getOrNull(0) ?: 1) - 1, buffer.cursorCol)
            'J'.code -> eraseDisplay(p.getOrNull(0) ?: 0)
            'K'.code -> eraseLine(p.getOrNull(0) ?: 0)
            'L'.code -> buffer.insertLines(n1(p, 0))
            'M'.code -> buffer.deleteLines(n1(p, 0))
            'P'.code -> buffer.deleteCells(n1(p, 0))
            '@'.code -> buffer.insertCells(n1(p, 0))
            'X'.code -> buffer.eraseChars(n1(p, 0))
            'S'.code -> buffer.scrollUp(n1(p, 0))
            'T'.code -> buffer.scrollDown(n1(p, 0))
            'r'.code -> buffer.setScrollRegion(
                n1(p, 0) - 1,
                (p.getOrNull(1) ?: buffer.rows) - 1
            )
            's'.code -> buffer.saveCursor()
            'u'.code -> buffer.restoreCursor()
            'm'.code -> applySgr(p)
            'h'.code -> setModes(p, true, isPrivate)
            'l'.code -> setModes(p, false, isPrivate)
            'n'.code -> respondToDsr(p.getOrNull(0) ?: 0, isPrivate)
            'c'.code -> {
                if (isPrivate) onResponse("\u001b[>1;2;0c".encodeToByteArray())
                else onResponse("\u001b[?1;2c".encodeToByteArray())
            }
            'b'.code -> buffer.repeatChar(n1(p, 0))
            'q'.code -> {
                val v = p.getOrNull(0) ?: 0
                buffer.cursorStyle = if (v in 1..6) v else 0
            }
            'p'.code -> if (intermediates.contains('$')) respondToDecrqm(p, isPrivate)
            else -> {}
        }
    }

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> buffer.eraseToEndOfScreen()
            1 -> buffer.eraseFromStartOfScreen()
            2 -> buffer.eraseScreen()
            3 -> buffer.eraseScreenAndScrollback()
        }
    }

    /** CSI 计数参数：缺省/显式 0 均按 1 处理（xterm 语义）。 */
    private fun n1(p: List<Int>, i: Int): Int = (p.getOrNull(i) ?: 1).coerceAtLeast(1)

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
                    if (set) { buffer.saveCursor(); buffer.enterAltScreen(clear = true) }
                    else { buffer.leaveAltScreen(); buffer.restoreCursor() }
                }
                private && p == 2004 -> buffer.bracketedPaste = set
                private && (p == 1000 || p == 1002 || p == 1003) -> buffer.mouseTracking = if (set) p else 0
                private && p == 1006 -> {
                    buffer.mouseSgr = set
                    if (set) buffer.mouseUrxvt = false
                }
                private && p == 1004 -> buffer.focusEvents = set
                private && p == 1007 -> buffer.alternateScroll = set
                private && p == 1015 -> {
                    buffer.mouseUrxvt = set
                    if (set) buffer.mouseSgr = false
                }
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
        buffer.mouseTracking = 0
        buffer.mouseSgr = false
        buffer.mouseUrxvt = false
        buffer.focusEvents = false
        buffer.alternateScroll = false
        buffer.cursorStyle = 0
        buffer.cursorColor = DEFAULT_CURSOR
        buffer.currentLink = null
        buffer.setScrollRegion(0, buffer.rows - 1)
        buffer.resetTabStops()
        buffer.lastPrintedCodePoint = 0
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
            line.touch()
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

    /** DCS $ q Pt ST（DECRQSS）：应答当前状态，无效查询回 DCS 0$r ST。 */
    private fun finishDcs() {
        val content = dcsBuf.toString()
        dcsBuf.clear()
        if (!content.startsWith("\$q")) return // tmux passthrough 等其它 DCS 忽略
        val query = content.removePrefix("\$q").trim()
        val resp = when {
            query.endsWith("r") -> { // DECSTBM
                "\u001bP1\$r${buffer.scrollTop + 1};${buffer.scrollBottom + 1}r\u001b\\"
            }
            query.endsWith("m") -> "\u001bP1\$r0m\u001b\\" // SGR：仅应答默认态
            query.endsWith("q") -> "\u001bP1\$r${buffer.cursorStyle} q\u001b\\" // DECSCUSR
            else -> "\u001bP0\$r\u001b\\"
        }
        onResponse(resp.encodeToByteArray())
    }

    /** CSI Ps $ p / CSI ? Ps $ p（DECRQM）：应答模式状态。 */
    private fun respondToDecrqm(params: List<Int>, isPrivate: Boolean) {
        val p = params.getOrNull(0) ?: return
        val state = if (isPrivate) {
            when (p) {
                1 -> if (buffer.applicationCursorKeys) 1 else 2
                6 -> if (buffer.originMode) 1 else 2
                7 -> if (buffer.autoWrap) 1 else 2
                25 -> if (buffer.cursorVisible) 1 else 2
                47, 1047, 1049 -> if (buffer.altScreen) 1 else 2
                1000 -> if (buffer.mouseTracking == 1000) 1 else 2
                1002 -> if (buffer.mouseTracking == 1002) 1 else 2
                1003 -> if (buffer.mouseTracking == 1003) 1 else 2
                1004 -> if (buffer.focusEvents) 1 else 2
                1006 -> if (buffer.mouseSgr) 1 else 2
                1007 -> if (buffer.alternateScroll) 1 else 2
                1015 -> if (buffer.mouseUrxvt) 1 else 2
                2004 -> if (buffer.bracketedPaste) 1 else 2
                else -> 0
            }
        } else {
            when (p) {
                4 -> if (buffer.insertMode) 1 else 2
                20 -> 2
                else -> 0
            }
        }
        if (state == 0) return
        onResponse("\u001b[${if (isPrivate) "?" else ""}$p;$state\$y".encodeToByteArray())
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
            4 -> handleOsc4Query(pt)
            10, 11 -> handleOscColorQuery(ps, pt)
            12 -> handleOsc12(pt)
            52 -> handleOsc52(pt)
            8 -> handleOsc8(pt)
            else -> {} // 忽略其它 OSC
        }
    }

    /** OSC 8 ; params ; URI —— 超链接开始/结束（URI 为空时结束）。 */
    private fun handleOsc8(pt: String) {
        val semi = pt.indexOf(';')
        val uri = if (semi >= 0) pt.substring(semi + 1) else pt
        buffer.currentLink = uri.ifEmpty { null }
    }

    /** OSC 12：查询/设置光标颜色。仅支持 #RRGGBB 与查询。 */
    private fun handleOsc12(pt: String) {
        val value = pt.trim()
        if (value == "?") {
            val rgb = if (buffer.cursorColor != DEFAULT_CURSOR) buffer.cursorColor else buffer.defaultCursorRgb
            onResponse("\u001b]12;${rgbToXTerm(rgb)}\u001b\\".encodeToByteArray())
            return
        }
        if (value.startsWith("#") && value.length == 7) {
            val hex = value.substring(1).toIntOrNull(16)
            if (hex != null) buffer.cursorColor = hex
        }
    }

    /** xterm 颜色格式：rgb:rrrr/gggg/bbbb（16 位/通道）。 */
    private fun rgbToXTerm(c: Int): String {
        fun ch(v: Int) = (v * 257).toString(16).padStart(4, '0')
        return "rgb:${ch(TerminalPalette.red(c))}/${ch(TerminalPalette.green(c))}/${ch(TerminalPalette.blue(c))}"
    }

    /** OSC 10/11 查询默认前景/背景色：TUI（如 herdr）据此决定对比色，必须应答。 */
    private fun handleOscColorQuery(ps: Int, pt: String) {
        if (pt.trimEnd() != "?") return // 暂不支持设置颜色
        val rgb = if (ps == 10) buffer.defaultFgRgb else buffer.defaultBgRgb
        onResponse("\u001b]$ps;${rgbToXTerm(rgb)}\u001b\\".encodeToByteArray())
    }

    /**
     * 主动向远端注入本端终端配色（OSC 10/11 + 基础 16 色调色板）。
     *
     * 用于 Mosh：mosh-server 会吞掉远端 TUI 发出的 OSC 10/11 查询，
     * 查询永远到不了手机，应用（如 herdr）只能拿到宿主机终端主题，
     * 导致手机浅色主题下远端却按深色渲染。把应答字节直接写进会话输入流，
     * 应用会像收到终端应答一样解析并采纳本端配色。
     */
    fun buildThemeSyncPayload(): ByteArray {
        val sb = StringBuilder()
        sb.append("\u001b]10;${rgbToXTerm(buffer.defaultFgRgb)}\u001b\\")
        sb.append("\u001b]11;${rgbToXTerm(buffer.defaultBgRgb)}\u001b\\")
        for (i in 0..15) {
            sb.append("\u001b]4;$i;${rgbToXTerm(TerminalPalette.PALETTE_256[i])}\u001b\\")
        }
        return sb.toString().encodeToByteArray()
    }

    /** OSC 4 ; i ; ? 调色板查询（可多对 i;?）。 */
    private fun handleOsc4Query(pt: String) {
        val parts = pt.split(';')
        var i = 0
        while (i + 1 < parts.size) {
            val idx = parts[i].toIntOrNull()
            if (idx != null && parts[i + 1] == "?" && idx in 0..255) {
                onResponse("\u001b]4;$idx;${rgbToXTerm(TerminalPalette.PALETTE_256[idx])}\u001b\\".encodeToByteArray())
            }
            i += 2
        }
    }

    /** OSC 52 ; Pc ; Pd —— Pd 为 base64 编码的剪贴板内容；Pd 为 "?" 时是查询（回空）。 */
    private fun handleOsc52(pt: String) {
        val semi = pt.indexOf(';')
        val pd = if (semi >= 0) pt.substring(semi + 1) else return
        if (pd == "?") {
            onResponse("\u001b]52;c;\u0007".encodeToByteArray())
            return
        }
        val bytes = dev.mssh.util.base64Decode(pd)
        if (bytes.isNotEmpty()) {
            onClipboardWrite(bytes.decodeToString())
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
        buffer.moveTo(0, 0) // eraseScreen 按 xterm 语义不再移动光标，reset 显式归位
    }
}

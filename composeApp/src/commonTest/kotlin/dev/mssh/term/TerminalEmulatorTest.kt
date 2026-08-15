package dev.mssh.term

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerminalEmulatorTest {

    private fun emu(cols: Int = 10, rows: Int = 5) =
        TerminalBuffer(cols, rows).let { TerminalEmulator(it) to it }

    private fun TerminalBuffer.lineText(row: Int): String {
        val sb = StringBuilder()
        for (c in visibleLines()[row].cells) {
            if (c.isWideTail) continue
            sb.append(c.codePoint.toChar())
        }
        return sb.toString().trimEnd()
    }

    @Test
    fun basicTextAndCursor() {
        val (e, b) = emu()
        e.writeText("hello")
        assertEquals("hello", b.lineText(0))
        assertEquals(0, b.cursorRow)
        assertEquals(5, b.cursorCol)
    }

    @Test
    fun carriageReturnAndLineFeed() {
        val (e, b) = emu()
        e.writeText("ab\rcd")
        assertEquals("cd", b.lineText(0))
        e.writeText("\r\nxy")
        assertEquals("xy", b.lineText(1))
        assertEquals(1, b.cursorRow)
        assertEquals(2, b.cursorCol)
    }

    @Test
    fun cursorPositioningCsi() {
        val (e, b) = emu()
        e.writeText("\u001b[3;4HX") // row 3, col 4 (1-based)
        assertEquals(2, b.cursorRow)
        assertEquals(4, b.cursorCol)
        assertEquals('X', b.lineAt(2).cells[3].codePoint.toChar())
    }

    @Test
    fun cursorMovement() {
        val (e, b) = emu()
        e.writeText("\u001b[2;2H") // row2 col2
        e.writeText("\u001b[2B\u001b[3C") // down 2, right 3
        assertEquals(3, b.cursorRow) // 1 + 2
        assertEquals(4, b.cursorCol) // 1 + 3
    }

    @Test
    fun sgrColors() {
        val (e, b) = emu()
        e.writeText("\u001b[31;1mX\u001b[0mY")
        val x = b.lineAt(0).cells[0]
        val y = b.lineAt(0).cells[1]
        assertEquals(TerminalPalette.BASIC_16[1], x.fg)
        assertTrue(x.attrs and CellAttr.BOLD != 0)
        assertEquals(DEFAULT_FG, y.fg)
        assertEquals(0, y.attrs)
    }

    @Test
    fun sgrTruecolor() {
        val (e, b) = emu()
        e.writeText("\u001b[38;2;10;20;30mX")
        assertEquals(TerminalPalette.rgb(10, 20, 30), b.lineAt(0).cells[0].fg)
    }

    @Test
    fun sgr256Color() {
        val (e, b) = emu()
        e.writeText("\u001b[38;5;196mX") // 196 = red
        assertEquals(TerminalPalette.PALETTE_256[196], b.lineAt(0).cells[0].fg)
    }

    @Test
    fun eraseDisplay() {
        val (e, b) = emu()
        e.writeText("abcdefghij") // fill row 0
        e.writeText("\r\nabcdefghij") // row 1
        e.writeText("\u001b[1;1H\u001b[2J") // erase all
        assertEquals("", b.lineText(0))
        assertEquals("", b.lineText(1))
    }

    @Test
    fun eraseLineToEnd() {
        val (e, b) = emu()
        e.writeText("abcdefghij")
        e.writeText("\u001b[1;3H\u001b[0K")
        assertEquals("ab", b.lineText(0))
    }

    @Test
    fun fullScreenScrollAccumulatesScrollback() {
        val (e, b) = emu()
        // 5 rows, write 3 lines then a newline triggers scroll
        e.writeText("one")
        e.writeText("\r\n")
        e.writeText("two")
        e.writeText("\r\n")
        e.writeText("three")
        e.writeText("\r\n")
        e.writeText("four")
        e.writeText("\r\n")
        e.writeText("five")
        e.writeText("\r\n")
        e.writeText("six")
        assertEquals(1, b.scrollbackSize())
        assertEquals("two", b.lineText(0))
        assertEquals("six", b.lineText(4))
    }

    @Test
    fun scrollRegionKeepsHeader() {
        val (e, b) = emu()
        e.writeText("\u001b[2;5r") // scroll region rows 2..5 (1-based)
        e.writeText("\u001b[1;1HHEADER")
        e.writeText("\u001b[2;1H")
        repeat(20) { e.writeText("x\n") } // scroll within region
        assertEquals("HEADER", b.lineText(0))
        // 回看不因区域滚动而增长
        assertEquals(0, b.scrollbackSize())
    }

    @Test
    fun altScreenSwitch() {
        val (e, b) = emu()
        e.writeText("main")
        e.writeText("\u001b[?1049h") // enter alt
        assertTrue(b.altScreen)
        assertEquals("", b.lineText(0))
        e.writeText("vim")
        assertEquals("vim", b.lineText(0))
        e.writeText("\u001b[?1049l") // leave alt
        assertFalse(b.altScreen)
        assertEquals("main", b.lineText(0))
    }

    @Test
    fun insertDeleteChars() {
        val (e, b) = emu()
        e.writeText("abcdef")
        e.writeText("\u001b[1;3H") // col 3
        e.writeText("\u001b[2P") // delete 2 chars
        assertEquals("abef", b.lineText(0))
        e.writeText("\u001b[1;3H\u001b[2@") // insert 2 blanks
        assertEquals("ab  ef", b.lineText(0))
    }

    @Test
    fun wideCharacterOccupiesTwoColumns() {
        val (e, b) = emu()
        e.writeText("A中B")
        val line = b.lineAt(0)
        assertEquals('A', line.cells[0].codePoint.toChar())
        assertEquals('中', line.cells[1].codePoint.toChar())
        assertTrue(line.cells[2].isWideTail)
        assertEquals('B', line.cells[3].codePoint.toChar())
        assertEquals(4, b.cursorCol)
    }

    @Test
    fun utf8MultibyteDecode() {
        val (e, b) = emu()
        e.write("中".encodeToByteArray())
        assertEquals('中', b.lineAt(0).cells[0].codePoint.toChar())
        assertEquals(2, b.cursorCol)
    }

    @Test
    fun decSpecialGraphics() {
        val (e, b) = emu()
        e.writeText("\u001b(0") // G0 = DEC special
        e.writeText("q") // ─
        assertEquals(0x2500, b.lineAt(0).cells[0].codePoint)
        e.writeText("\u001b(B") // back to ASCII
        e.writeText("q")
        assertEquals('q'.code, b.lineAt(0).cells[1].codePoint)
    }

    @Test
    fun oscTitle() {
        val (e, _) = emu()
        var title = ""
        e.onTitleChange = { title = it }
        e.writeText("\u001b]0;hello world\u0007")
        assertEquals("hello world", title)
    }

    @Test
    fun backspaceAndTab() {
        val (e, b) = emu()
        e.writeText("abc\u0008") // backspace
        assertEquals(2, b.cursorCol)
        e.writeText("\u0009") // tab to col 8
        assertEquals(8, b.cursorCol)
    }

    @Test
    fun selectionText() {
        val (e, b) = emu()
        e.writeText("hello\r\nworld")
        val sel = TerminalSelection(b)
        sel.start(0, 0)
        sel.extend(0, 4)
        assertEquals("hello", sel.selectedText())
        sel.start(0, 0)
        sel.extend(1, 4)
        assertEquals("hello\nworld", sel.selectedText())
    }

    @Test
    fun resizePreservesContent() {
        val (e, b) = emu()
        e.writeText("abcdefghij")
        b.resize(5, 5)
        assertEquals("abcde", b.lineText(0))
        b.resize(10, 5)
        assertEquals("abcde", b.lineText(0))
    }

    @Test
    fun cursorSaveRestore() {
        val (e, b) = emu()
        e.writeText("\u001b[4;5H")
        e.writeText("\u001b7") // save
        e.writeText("\u001b[1;1H")
        e.writeText("\u001b8") // restore
        assertEquals(3, b.cursorRow)
        assertEquals(4, b.cursorCol)
    }

    @Test
    fun repeatCharacter() {
        val (e, b) = emu()
        e.writeText("a\u001b[3b")
        assertEquals("aaaa", b.lineText(0))
    }

    @Test
    fun cursorStyleSequences() {
        val (e, b) = emu()
        e.writeText("\u001b[2 q")
        assertEquals(2, b.cursorStyle)
        e.writeText("\u001b[4 q")
        assertEquals(4, b.cursorStyle)
        e.writeText("\u001b[6 q")
        assertEquals(6, b.cursorStyle)
        e.writeText("\u001b[0 q")
        assertEquals(0, b.cursorStyle)
        e.writeText("\u001b[ q") // 无参数 → 默认
        assertEquals(0, b.cursorStyle)
        e.writeText("\u001b[9 q") // 非法值 → 默认
        assertEquals(0, b.cursorStyle)
    }

    @Test
    fun osc8Hyperlinks() {
        val (e, b) = emu()
        e.writeText("\u001b]8;;https://example.com\u0007link\u001b]8;;\u0007end")
        assertEquals("https://example.com", b.lineAt(0).cells[0].link)
        assertEquals("https://example.com", b.lineAt(0).cells[3].link)
        assertNull(b.lineAt(0).cells[4].link)
    }

    @Test
    fun osc8HyperlinkParamsIgnoredAndWideTailInherits() {
        val (e, b) = emu()
        // params 段（id=...）应忽略，URI 取第二个分号后
        e.writeText("\u001b]8;id=42;https://x.dev\u0007中文\u001b]8;;\u0007")
        assertEquals("https://x.dev", b.lineAt(0).cells[0].link)
        assertEquals("https://x.dev", b.lineAt(0).cells[1].link) // 宽字符尾巴继承链接
    }

    @Test
    fun osc12CursorColorQueryAndSet() {
        val (e, _) = emu()
        val responses = StringBuilder()
        e.onResponse = { responses.append(it.decodeToString()) }
        e.writeText("\u001b]12;?\u0007")
        assertTrue(responses.toString().startsWith("\u001b]12;rgb:"), responses.toString())
        responses.clear()
        e.writeText("\u001b]12;#ff0000\u0007\u001b]12;?\u0007")
        assertTrue(responses.toString().contains("rgb:ffff/0000/0000"), responses.toString())
    }

    @Test
    fun decrqmModes() {
        val (e, _) = emu()
        val responses = StringBuilder()
        e.onResponse = { responses.append(it.decodeToString()) }
        e.writeText("\u001b[?2004h\u001b[?2004\$p")
        assertEquals("\u001b[?2004;1\$y", responses.toString())
        responses.clear()
        e.writeText("\u001b[?2004l\u001b[?2004\$p")
        assertEquals("\u001b[?2004;2\$y", responses.toString())
        responses.clear()
        // 未知模式不应答
        e.writeText("\u001b[?9999\$p")
        assertEquals("", responses.toString())
        responses.clear()
        // 标准模式：插入模式
        e.writeText("\u001b[4h\u001b[4\$p")
        assertEquals("\u001b[4;1\$y", responses.toString())
    }

    @Test
    fun decrqssResponses() {
        val (e, _) = emu()
        val responses = StringBuilder()
        e.onResponse = { responses.append(it.decodeToString()) }
        e.writeText("\u001bP\$qr\u001b\\") // DECSTBM
        assertEquals("\u001bP1\$r1;5r\u001b\\", responses.toString())
        responses.clear()
        e.writeText("\u001bP\$qm\u001b\\") // SGR（应答默认态）
        assertEquals("\u001bP1\$r0m\u001b\\", responses.toString())
        responses.clear()
        e.writeText("\u001b[2 q\u001bP\$qq\u001b\\") // DECSCUSR
        assertEquals("\u001bP1\$r2 q\u001b\\", responses.toString())
        responses.clear()
        e.writeText("\u001bP\$qz\u001b\\") // 未知查询 → 无效
        assertEquals("\u001bP0\$r\u001b\\", responses.toString())
    }

    @Test
    fun da2Response() {
        val (e, _) = emu()
        val responses = StringBuilder()
        e.onResponse = { responses.append(it.decodeToString()) }
        e.writeText("\u001b[>c")
        assertEquals("\u001b[>1;2;0c", responses.toString())
    }

    @Test
    fun focusAlternateScrollAndUrxvtModes() {
        val (e, b) = emu()
        e.writeText("\u001b[?1004h\u001b[?1007h\u001b[?1015h")
        assertTrue(b.focusEvents)
        assertTrue(b.alternateScroll)
        assertTrue(b.mouseUrxvt)
        assertFalse(b.mouseSgr)
        // 1006 与 1015 互斥
        e.writeText("\u001b[?1006h")
        assertTrue(b.mouseSgr)
        assertFalse(b.mouseUrxvt)
        e.writeText("\u001b[?1015h")
        assertTrue(b.mouseUrxvt)
        assertFalse(b.mouseSgr)
        e.writeText("\u001b[?1004l\u001b[?1007l")
        assertFalse(b.focusEvents)
        assertFalse(b.alternateScroll)
    }

    @Test
    fun fullResetClearsNewState() {
        val (e, b) = emu()
        e.writeText("\u001b[?1004h\u001b[?1007h\u001b[?1015h\u001b[2 q")
        e.writeText("\u001b]12;#00ff00\u0007")
        e.writeText("\u001b]8;;https://x\u0007a\u001b]8;;\u0007")
        assertTrue(b.focusEvents && b.alternateScroll && b.mouseUrxvt)
        e.writeText("\u001bc") // RIS
        assertFalse(b.focusEvents)
        assertFalse(b.alternateScroll)
        assertFalse(b.mouseUrxvt)
        assertEquals(0, b.cursorStyle)
        assertEquals(DEFAULT_CURSOR, b.cursorColor)
        assertNull(b.currentLink)
        assertEquals(0, b.lastPrintedCodePoint)
    }
}

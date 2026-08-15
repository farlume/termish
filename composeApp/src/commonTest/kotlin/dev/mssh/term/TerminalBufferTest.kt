package dev.mssh.term

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** TerminalBuffer 回归测试：覆盖 resize 收缩、宽字符尾巴颜色继承等已修 bug。 */
class TerminalBufferTest {

    @Test
    fun lineVersionBumpsOnWriteNotOnCursorMove() {
        val b = TerminalBuffer(10, 5)
        b.putChar('a'.code)
        val v0 = b.lineAt(0).version
        // 光标移动 / 模式切换不改变内容，版本号不动
        b.moveTo(2, 2)
        b.setScrollRegion(0, 4)
        assertEquals(v0, b.lineAt(0).version)
        // 写入内容 → 版本递增
        b.putChar('b'.code)
        assertTrue(b.lineAt(0).version > v0)
    }

    @Test
    fun lineVersionBumpsOnEraseAndResize() {
        val b = TerminalBuffer(10, 5)
        b.putChar('x'.code)
        val v0 = b.lineAt(0).version
        b.eraseLine()
        assertTrue(b.lineAt(0).version > v0)
        b.putChar('y'.code)
        val v1 = b.lineAt(0).version
        b.resize(6, 5)
        assertTrue(b.lineAt(0).version > v1)
    }

    @Test
    fun fullScreenScrollKeepsLineIdentityAndVersion() {
        val b = TerminalBuffer(10, 5)
        repeat(5) { r -> b.lineAt(r).let { it.cells[0].codePoint = 'a'.code + r; it.touch() } }
        val first = b.lineAt(0)
        val firstVersion = first.version
        b.scrollUp(1)
        // 旧首行进入回看，仍是同一实例、版本未变（内容未改写）
        assertTrue(b.scrollbackSize() == 1)
        assertTrue(b.absLine(0) === first)
        assertEquals(firstVersion, b.absLine(0).version)
        // 可见区首行是原第 2 行，实例未变
        assertTrue(b.lineAt(0) !== first)
    }

    @Test
    fun regionScrollBumpsDestinationLines() {
        val b = TerminalBuffer(10, 5)
        repeat(5) { r -> b.lineAt(r).touch() }
        val versions = (0..4).map { b.lineAt(it).version }
        b.setScrollRegion(1, 3)
        b.scrollUp(1)
        // 区域内的行内容被改写 → 版本递增；区域外不变
        assertTrue(b.lineAt(1).version > versions[1])
        assertTrue(b.lineAt(2).version > versions[2])
        assertEquals(versions[4], b.lineAt(4).version)
    }

    private fun TerminalBuffer.lineText(row: Int): String {
        val sb = StringBuilder()
        for (c in visibleLines()[row].cells) {
            if (c.isWideTail) continue
            sb.append(c.codePoint.toChar())
        }
        return sb.toString().trimEnd()
    }

    @Test
    fun resizeShrinkKeepsCursorVisible() {
        // 键盘弹起场景：收缩行数应丢弃光标下方空白行，提示符留在可视区
        val b = TerminalBuffer(10, 5)
        val e = TerminalEmulator(b)
        e.writeText("line0\r\nline1\r\n$ ")
        assertEquals(2, b.cursorRow)

        b.resize(10, 3)

        assertEquals(2, b.cursorRow)
        assertEquals("line0", b.lineText(0))
        assertEquals("line1", b.lineText(1))
        assertEquals("$", b.lineText(2))
        // 丢弃的是底部空白行，顶部内容不应被挤入回看
        assertEquals(0, b.scrollbackSize())
    }

    @Test
    fun resizeShrinkFullScreenFallsBackToScrollback() {
        // 满屏文字没有空白行可丢：顶部行进入回看，光标钳在可视区
        val b = TerminalBuffer(4, 3)
        val e = TerminalEmulator(b)
        e.writeText("aaaa\r\nbbbb\r\ncccc")
        b.resize(4, 2)
        assertTrue(b.cursorRow in 0..1)
        assertEquals("cccc", b.lineText(1))
    }

    @Test
    fun wideCharTailInheritsColors() {
        // 宽字符尾巴必须继承头的颜色/属性，否则带底色的行上右半格露默认背景
        val b = TerminalBuffer(10, 3)
        val e = TerminalEmulator(b)
        e.writeText("[47m中[0m")
        val line = b.visibleLines()[0]
        assertEquals('中'.code, line.cells[0].codePoint)
        assertTrue(line.cells[1].isWideTail)
        assertEquals(line.cells[0].bg, line.cells[1].bg)
        assertEquals(line.cells[0].attrs, line.cells[1].attrs)
    }

    @Test
    fun narrowOverwriteWideHeadClearsTail() {
        val b = TerminalBuffer(10, 3)
        val e = TerminalEmulator(b)
        e.writeText("中文\rxy")
        val line = b.visibleLines()[0]
        assertEquals('x'.code, line.cells[0].codePoint)
        assertFalse(line.cells[1].isWideTail)
        assertEquals('y'.code, line.cells[1].codePoint)
    }

    @Test
    fun writeOnWideTailClearsHead() {
        val b = TerminalBuffer(10, 3)
        val e = TerminalEmulator(b)
        e.writeText("中")   // 占 0-1 列，光标到 2
        e.writeText("[1D") // 光标左移一格，落在尾巴上
        e.writeText("x")
        val line = b.visibleLines()[0]
        // 头被清掉，x 写在前尾巴位置
        assertEquals(' '.code, line.cells[0].codePoint)
        assertEquals('x'.code, line.cells[1].codePoint)
        assertFalse(line.cells[1].isWideTail)
    }

    @Test
    fun bracketedPasteModeTracked() {
        val b = TerminalBuffer(10, 3)
        val e = TerminalEmulator(b)
        assertFalse(b.bracketedPaste)
        e.writeText("[?2004h")
        assertTrue(b.bracketedPaste)
        e.writeText("[?2004l")
        assertFalse(b.bracketedPaste)
    }

    @Test
    fun osc52WritesClipboard() {
        val b = TerminalBuffer(10, 3)
        val e = TerminalEmulator(b)
        var clip = ""
        e.onClipboardWrite = { clip = it }
        // "hi" 的 base64 是 aGk=
        e.writeText("]52;c;aGk=")
        assertEquals("hi", clip)
    }

    @Test
    fun mouseTrackingModesNegotiated() {
        // herdr 实际发送的协商序列：先 reset 再 enable 1000/1002/1003/1015/1006
        val b = TerminalBuffer(10, 3)
        val e = TerminalEmulator(b)
        e.writeText("\u001b[?1006l\u001b[?1000l")
        assertEquals(0, b.mouseTracking)
        assertFalse(b.mouseSgr)
        e.writeText("\u001b[?1000h\u001b[?1002h\u001b[?1003h\u001b[?1015h\u001b[?1006h")
        assertEquals(1003, b.mouseTracking)
        assertTrue(b.mouseSgr)
    }
}

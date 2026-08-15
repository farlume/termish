package dev.mssh.term

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** TerminalBuffer 回归测试：覆盖 resize 收缩、宽字符尾巴颜色继承等已修 bug。 */
class TerminalBufferTest {

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
}

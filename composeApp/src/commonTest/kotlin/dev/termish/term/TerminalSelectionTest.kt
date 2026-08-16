package dev.termish.term

import kotlin.test.Test
import kotlin.test.assertEquals

/** TerminalSelection 复制语义：折行续行不插入换行、普通行间保留换行。 */
class TerminalSelectionTest {

    @Test
    fun wrappedRowsJoinWithoutNewline() {
        val b = TerminalBuffer(5, 3)
        "abcdefghij".forEach { b.putChar(it.code) }
        // 10 个字符折成两行：line0="abcde"（wrapped），line1="fghij"
        val sel = TerminalSelection(b)
        sel.start(0, 0)
        sel.extend(1, 4)

        assertEquals("abcdefghij", sel.selectedText())
    }

    @Test
    fun unwrappedRowsKeepNewline() {
        val b = TerminalBuffer(5, 3)
        "abc".forEach { b.putChar(it.code) }
        b.newline()
        "def".forEach { b.putChar(it.code) }
        val sel = TerminalSelection(b)
        sel.start(0, 0)
        sel.extend(1, 2)

        assertEquals("abc\ndef", sel.selectedText())
    }

    @Test
    fun selectionStartingInsideWrappedRowStartsNewParagraph() {
        val b = TerminalBuffer(5, 3)
        "abcdefghij".forEach { b.putChar(it.code) }
        val sel = TerminalSelection(b)
        // 从续行（line1）中间开始选，不应把前面没选中的内容接进来
        sel.start(1, 2)
        sel.extend(1, 4)

        assertEquals("hij", sel.selectedText())
    }
}

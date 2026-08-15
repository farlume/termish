package dev.mssh.term

import kotlin.test.Test
import kotlin.test.assertEquals

class TerminalColorTest {
    @Test
    fun argbToRgbExtractsChannels() {
        assertEquals(0x0e0f13, argbToRgb(0xff0e0f13L))
        assertEquals(0xffffff, argbToRgb(0xffffffffL))
        assertEquals(0x58a6ff, argbToRgb(0xff58a6ffL))
    }
}

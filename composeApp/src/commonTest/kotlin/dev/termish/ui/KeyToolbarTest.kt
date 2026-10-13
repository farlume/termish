package dev.termish.ui

import kotlin.test.Test
import kotlin.test.assertContentEquals

class KeyToolbarTest {
    private val cursorKeys =
        listOf(
            SpecialKey.UP to 'A',
            SpecialKey.DOWN to 'B',
            SpecialKey.RIGHT to 'C',
            SpecialKey.LEFT to 'D',
            SpecialKey.HOME to 'H',
            SpecialKey.END to 'F',
        )

    @Test
    fun normalCursorKeysUseCsi() {
        for ((key, final) in cursorKeys) {
            assertContentEquals(byteArrayOf(0x1b, 0x5b, final.code.toByte()), specialKeyBytes(key, false), key.name)
        }
    }

    @Test
    fun applicationCursorKeysUseSs3() {
        for ((key, final) in cursorKeys) {
            assertContentEquals(byteArrayOf(0x1b, 0x4f, final.code.toByte()), specialKeyBytes(key, true), key.name)
        }
    }

    @Test
    fun otherSpecialKeysAreIndependentOfCursorMode() {
        val sequences =
            listOf(
                SpecialKey.ESC to "\u001b",
                SpecialKey.TAB to "\t",
                SpecialKey.ENTER to "\r",
                SpecialKey.SHIFT_TAB to "\u001b[Z",
                SpecialKey.DEL to "\u007f",
                SpecialKey.CTRL_C to "\u0003",
                SpecialKey.CTRL_D to "\u0004",
                SpecialKey.CTRL_L to "\u000c",
                SpecialKey.CTRL_E to "\u0005",
                SpecialKey.CTRL_R to "\u0012",
                SpecialKey.CTRL_Z to "\u001a",
                SpecialKey.CTRL_BACKSLASH to "\u001c",
                SpecialKey.PGUP to "\u001b[5~",
                SpecialKey.PGDN to "\u001b[6~",
                SpecialKey.F1 to "\u001bOP",
                SpecialKey.F2 to "\u001bOQ",
                SpecialKey.F3 to "\u001bOR",
                SpecialKey.F4 to "\u001bOS",
                SpecialKey.F5 to "\u001b[15~",
                SpecialKey.F6 to "\u001b[17~",
                SpecialKey.F7 to "\u001b[18~",
                SpecialKey.F8 to "\u001b[19~",
                SpecialKey.F9 to "\u001b[20~",
                SpecialKey.F10 to "\u001b[21~",
                SpecialKey.F11 to "\u001b[23~",
                SpecialKey.F12 to "\u001b[24~",
                SpecialKey.CTRL to "",
                SpecialKey.ALT to "",
            )
        for ((key, sequence) in sequences) {
            for (applicationCursorKeys in listOf(false, true)) {
                assertContentEquals(sequence.encodeToByteArray(), specialKeyBytes(key, applicationCursorKeys), key.name)
            }
        }
    }
}

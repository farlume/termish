package dev.mssh.mosh

import dev.mssh.term.CellAttr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 本地预测回显（speculative local echo）回归测试。 */
class PredictionTest {

    private var now = 0L
    private fun layer() = PredictionLayer(nowMs = { now })

    @Test
    fun printableCharPredictsWithUnderline() {
        val base = ShadowTerminal.create(80, 24)
        val l = layer()
        l.onConfirmed(base)

        assertTrue(l.onUserInput("a".encodeToByteArray(), 100))
        val shown = l.currentForDisplay()!!
        val cell = shown.buffer.lineAt(0).cells[0]
        assertEquals('a'.code, cell.codePoint)
        assertTrue(cell.attrs and CellAttr.UNDERLINE != 0) // 预测格带下划线标记
        // 确认态本身不受影响
        assertEquals(' '.code, base.buffer.lineAt(0).cells[0].codePoint)
    }

    @Test
    fun controlCharDropsPrediction() {
        val base = ShadowTerminal.create(80, 24)
        val l = layer()
        l.onConfirmed(base)
        l.onUserInput("a".encodeToByteArray(), 100)

        assertFalse(l.onUserInput(byteArrayOf(0x08), 100)) // 退格 → 放弃整段预测
        assertTrue(l.currentForDisplay() === base) // 回确认态
    }

    @Test
    fun lowRttDisablesPrediction() {
        val base = ShadowTerminal.create(80, 24)
        val l = layer()
        l.onConfirmed(base)
        // mosh SRTT_TRIGGER_LOW=20：低延迟链路不预测
        assertFalse(l.onUserInput("a".encodeToByteArray(), 5))
    }

    @Test
    fun altScreenDisablesPrediction() {
        val base = ShadowTerminal.create(80, 24)
        base.buffer.enterAltScreen(true)
        val l = layer()
        l.onConfirmed(base)
        assertFalse(l.onUserInput("a".encodeToByteArray(), 100)) // vim/tmux 等不预测
    }

    @Test
    fun confirmedDropsPrediction() {
        val base = ShadowTerminal.create(80, 24)
        val next = base.fork()
        val l = layer()
        l.onConfirmed(base)
        l.onUserInput("a".encodeToByteArray(), 100)

        l.onConfirmed(next)
        assertTrue(l.currentForDisplay() === next) // 确认一到即切确认态
    }

    @Test
    fun glitchTimeoutDropsPrediction() {
        val base = ShadowTerminal.create(80, 24)
        val l = layer()
        l.onConfirmed(base)
        now = 1000
        l.onUserInput("a".encodeToByteArray(), 100)
        assertFalse(l.glitchTimedOut())

        now = 5000 // 超过 3s 悬挂
        assertTrue(l.glitchTimedOut())
    }
}

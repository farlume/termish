package dev.termish.mosh

import dev.termish.term.CellAttr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

        assertTrue(l.onUserInput("a".encodeToByteArray(), 100, 0u))
        val shown = l.currentForDisplay()!!
        val cell = shown.buffer.lineAt(0).cells[0]
        assertEquals('a'.code, cell.codePoint)
        assertTrue(cell.attrs and CellAttr.UNDERLINE != 0) // send_interval>80 → flagging 下划线
        // 确认态本身不受影响
        assertEquals(' '.code, base.buffer.lineAt(0).cells[0].codePoint)
    }

    @Test
    fun lowSendIntervalDisablesUnderline() {
        val base = ShadowTerminal.create(80, 24)
        val l = layer()
        l.onConfirmed(base)
        // 30 < send_interval <= 50：预测启用（>30）但不下划线（<=50，mosh FLAG_TRIGGER）
        assertTrue(l.onUserInput("a".encodeToByteArray(), 40, 0u))
        val cell = l.currentForDisplay()!!.buffer.lineAt(0).cells[0]
        assertEquals('a'.code, cell.codePoint)
        assertTrue(cell.attrs and CellAttr.UNDERLINE == 0)
    }

    @Test
    fun unsupportedControlCharSkipsPrediction() {
        val base = ShadowTerminal.create(80, 24)
        val l = layer()
        l.onConfirmed(base)
        l.onUserInput("a".encodeToByteArray(), 100, 0u)

        assertFalse(l.onUserInput(byteArrayOf(0x08), 100, 0u)) // BS 控制字符不支持 → 本段不预测
        assertTrue(l.currentForDisplay() === base) // 回确认态
    }

    @Test
    fun backspacePredictsErase() {
        val base = ShadowTerminal.create(80, 24)
        val l = layer()
        l.onConfirmed(base)
        l.onUserInput("ab".encodeToByteArray(), 100, 0u)
        assertTrue(l.onUserInput(byteArrayOf(0x7f), 100, 0u)) // 删 b

        val shown = l.currentForDisplay()!!
        assertEquals('a'.code, shown.buffer.lineAt(0).cells[0].codePoint)
        assertEquals(' '.code, shown.buffer.lineAt(0).cells[1].codePoint) // b 被抹掉
        assertEquals(1, shown.buffer.cursorCol) // 光标回退
    }

    @Test
    fun carriageReturnPredictsNewline() {
        val base = ShadowTerminal.create(80, 24)
        val l = layer()
        l.onConfirmed(base)
        l.onUserInput("ls".encodeToByteArray(), 100, 0u)
        assertTrue(l.onUserInput(byteArrayOf(0x0d), 100, 0u)) // Enter → 回显 \r\n

        val shown = l.currentForDisplay()!!
        assertEquals(1, shown.buffer.cursorRow)
        assertEquals(0, shown.buffer.cursorCol)
    }

    @Test
    fun leftArrowPredictsCursorMove() {
        val base = ShadowTerminal.create(80, 24)
        val l = layer()
        l.onConfirmed(base)
        l.onUserInput("ab".encodeToByteArray(), 100, 0u)
        assertTrue(l.onUserInput("\u001b[D".encodeToByteArray(), 100, 0u)) // ←
        assertEquals(1, l.currentForDisplay()!!.buffer.cursorCol)
    }

    @Test
    fun lowRttDisablesPrediction() {
        val base = ShadowTerminal.create(80, 24)
        val l = layer()
        l.onConfirmed(base)
        // send_interval<=20（SRTT_TRIGGER_LOW）：低延迟链路不预测
        assertFalse(l.onUserInput("a".encodeToByteArray(), 20, 0u))
    }

    @Test
    fun altScreenPredictsPrintableOnly() {
        val base = ShadowTerminal.create(80, 24)
        base.buffer.enterAltScreen(true)
        val l = layer()
        l.onConfirmed(base)
        // alt 屏（vim/tmux pane 内的 shell 等回显路径）：可打印字符仍预测
        assertTrue(l.onUserInput("a".encodeToByteArray(), 100, 0u))
        assertEquals('a'.code, l.currentForDisplay()!!.buffer.lineAt(0).cells[0].codePoint)
        // 控制字节不预测：全屏程序里语义各异，等回显
        assertFalse(l.onUserInput(byteArrayOf(0x7f), 100, 0u))
    }

    @Test
    fun predictionSurvivesConfirmedFrameWithoutEchoAck() {
        val base = ShadowTerminal.create(80, 24)
        val next = base.fork() // echoAck 仍为 0（未覆盖输入承载帧 1）
        val l = layer()
        l.onConfirmed(base)
        l.onUserInput("a".encodeToByteArray(), 100, 0u)

        l.onConfirmed(next)
        // 无回显确认的帧到达：预测跨帧存活（不再整段丢弃 → 无回退闪烁）
        val shown = l.currentForDisplay()!!
        assertTrue(shown !== next)
        assertEquals('a'.code, shown.buffer.lineAt(0).cells[0].codePoint)
    }

    @Test
    fun echoAckAdvancingDropsPrediction() {
        val base = ShadowTerminal.create(80, 24)
        val next = base.fork()
        next.echoAck = 1u // 已覆盖承载帧（lastSentNum 0 + 1）
        val l = layer()
        l.onConfirmed(base)
        l.onUserInput("a".encodeToByteArray(), 100, 0u)

        l.onConfirmed(next)
        assertTrue(l.currentForDisplay() === next) // 回显已到 → 收编切回确认态
    }

    @Test
    fun glitchTimeoutDropsPrediction() {
        val base = ShadowTerminal.create(80, 24)
        val l = layer()
        l.onConfirmed(base)
        now = 1000
        l.onUserInput("a".encodeToByteArray(), 100, 0u)
        assertFalse(l.glitchTimedOut())

        now = 5000 // 超过 3s 悬挂
        assertTrue(l.glitchTimedOut())
        l.dropPrediction()
        assertTrue(l.currentForDisplay() === base)
        assertFalse(l.glitchTimedOut()) // 丢弃后不重复触发
    }

    @Test
    fun resizeResetsPrediction() {
        val base = ShadowTerminal.create(80, 24)
        val l = layer()
        l.onConfirmed(base)
        l.onUserInput("a".encodeToByteArray(), 100, 0u)
        l.reset()
        assertTrue(l.currentForDisplay() === base)
    }
}

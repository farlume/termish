package dev.mssh.mosh

/**
 * 本地预测回显（对应协议 预测引擎 的简化版）。
 *
 * 原生 mosh-client 打字"即时"的关键：高 RTT 链路上，每个击键先在本地
 * framebuffer 上预测渲染，服务器确认后校正。确认语义对齐 mosh：
 * - 触发用 send_interval（SRTT/2 clamp 20..250ms）：>30ms 开 / <=20ms 关，
 *   带迟滞（mosh SRTT_TRIGGER_LOW/HIGH）；下划线标记（flagging）>80ms 开、
 *   <=50ms 关（mosh FLAG_TRIGGER_LOW/HIGH）
 * - 预测跨确认帧存活：echo_ack 未覆盖承载帧时，在每个新确认态上重放预测，
 *   避免"字符出现→确认帧不含回显→消失→回显帧到→再出现"的回退闪烁
 * - echo_ack >= 承载帧（mosh ECHO_TIMEOUT=50ms 后的 late ack）即收编：
 *   回显（或"无回显"的事实，如密码输入）已包含在确认态里
 * - echo_ack >= 承载帧（mosh ECHO_TIMEOUT=50ms 后的 late ack）即收编：
 *   回显（或"无回显"的事实，如密码输入）已包含在确认态里
 * - 备用屏（vim/tmux/herdr 等全屏程序）只预测可打印字符：pane 内 shell /
 *   vim 插入模式的回显路径受益；控制字节语义因程序而异不预测（mosh 原生
 *   则全量预测 + per-cell 收编）；普通屏含不支持的控制字节放弃本段；
 *   大粘贴（>128 字节，mosh 阈值 100）不预测
 */
internal class PredictionLayer(
    private val nowMs: () -> Long,
) {
    private var confirmed: ShadowTerminal? = null
    private var predicted: ShadowTerminal? = null

    /** 尚未被 echo_ack 覆盖的输入字节（重放预测用）。 */
    private var pending = ByteArray(0)

    /** pending 尾部字节所在发送帧的估计（输入时刻 lastSentNum+1）；
     *  echoAck >= 它即视为回显已到（同帧或更早帧里的前缀字节必然也已确认）。 */
    private var pendingCarriedBy: ULong = 0uL
    private var predictedAt = 0L
    private var srttTrigger = false
    private var flagging = false

    // mosh terminaloverlay.h 的触发阈值（均作用于 send_interval，非裸 SRTT）
    private val srttTriggerLow = 20L
    private val srttTriggerHigh = 30L
    private val flagTriggerLow = 50L
    private val flagTriggerHigh = 80L
    private val glitchTimeout = 3000L
    private val maxPending = 128

    /** 会话收到新确认状态时调用：echo_ack 收编 + 存活预测跨帧重放。 */
    fun onConfirmed(shadow: ShadowTerminal) {
        confirmed = shadow
        if (pending.isEmpty()) {
            predicted = null
            return
        }
        if (shadow.echoAck >= pendingCarriedBy) {
            pending = ByteArray(0)
            predicted = null
            return
        }
        // 预测尚未被确认：在新确认态上重建（COW 分叉 O(行数) + 重放 O(pending)），
        // 与 mosh 把存活 overlay 重新 apply 到每个新帧等价
        predicted = shadow.predictInput(pending, flagging)
    }

    /**
     * 用户输入到达时调用（在发给 SSP 之前）。返回是否产生了可显示的预测。
     */
    fun onUserInput(bytes: ByteArray, sendIntervalMs: Long, lastSentNum: ULong): Boolean {
        // 触发迟滞（mosh 预测触发 的 srtt_trigger / flagging）
        if (sendIntervalMs > srttTriggerHigh) {
            srttTrigger = true
        } else if (sendIntervalMs <= srttTriggerLow && predicted == null) {
            srttTrigger = false // mosh：仅在没有活跃预测时才允许关闭
        }
        if (sendIntervalMs > flagTriggerHigh) {
            flagging = true
        } else if (sendIntervalMs <= flagTriggerLow) {
            flagging = false
        }

        val base = confirmed ?: return false
        if (!srttTrigger) return false
        val altScreen = base.buffer.altScreen

        pending += bytes
        pendingCarriedBy = lastSentNum + 1u
        if (pending.size > maxPending) {
            // 大粘贴：放弃预测（mosh process_user_input 里 paste 直接 reset）
            pending = ByteArray(0)
            predicted = null
            return false
        }
        if (altScreen && pending.any {
                val v = it.toInt() and 0xff
                v < 0x20 || v == 0x7f
            }
        ) {
            // alt 屏只预测可打印字符段：控制字节在全屏程序里语义各异
            //（vim 普通模式 x 是删字符不是退格），等回显；pending 保留待收编
            predicted = null
            return false
        }

        val p = base.predictInput(pending, flagging)
        predicted = p
        if (p != null) {
            predictedAt = nowMs()
            return true
        }
        // 含不支持的控制字节：本段不预测（pending 保留，等 echo_ack 收编）
        return false
    }

    /** 窗口尺寸变化：几何失效，丢弃预测等确认态（mosh process_resize → reset）。 */
    fun reset() {
        predicted = null
        pending = ByteArray(0)
    }

    /** 预测悬挂过久：丢弃预测回确认态（下次输入重新开始）。 */
    fun dropPrediction() {
        predicted = null
        pending = ByteArray(0)
    }

    /** 当前应展示的状态：有预测显示预测，否则确认态。 */
    fun currentForDisplay(): ShadowTerminal? = predicted ?: confirmed

    /** 预测悬挂过久（服务器一直未确认/期间有输出）→ 丢弃并回确认态。 */
    fun glitchTimedOut(): Boolean = predicted != null && nowMs() - predictedAt > glitchTimeout
}

package dev.mssh.mosh

/**
 * 本地预测回显（对应协议 预测引擎 的简化版）。
 *
 * 原生 mosh-client 打字"即时"的关键：高 RTT 链路上，每个击键先在本地
 * framebuffer 上预测渲染（下划线标记，mosh SRTT_TRIGGER_LOW=20ms 才启用），
 * 服务器确认后校正。KMP 此前直接渲染确认态，每个字符都等一个完整 RTT。
 *
 * v1 策略（从简且保守）：
 * - 仅当 SRTT >= 20ms 且非备用屏（vim/tmux 等全屏程序）时预测
 * - 只预测可打印字符段；出现控制字节/大粘贴即放弃整段预测
 * - 每次按键从确认态重新预测（COW 分叉 O(行数)，重放待确认字节 O(字节)）
 * - 任一确认态到达即丢弃预测切回确认态；预测悬挂超过 3s 同样丢弃
 */
internal class PredictionLayer(
    private val nowMs: () -> Long,
) {
    private var confirmed: ShadowTerminal? = null
    private var predicted: ShadowTerminal? = null
    private var pending = ByteArray(0)
    private var predictedAt = 0L

    private val srttTriggerLow = 20L // mosh SRTT_TRIGGER_LOW
    private val glitchTimeout = 3000L
    private val maxPending = 128

    /** 会话收到新确认状态时调用：丢弃预测，切回确认态。 */
    fun onConfirmed(shadow: ShadowTerminal) {
        confirmed = shadow
        predicted = null
        pending = ByteArray(0)
    }

    /**
     * 用户输入到达时调用（在发给 SSP 之前）。返回是否产生了可显示的预测。
     */
    fun onUserInput(bytes: ByteArray, srttMs: Long): Boolean {
        val base = confirmed ?: return false
        if (srttMs < srttTriggerLow) return false // 低延迟链路不需要预测
        if (base.buffer.altScreen) return false // 全屏程序不预测

        pending += bytes
        val hasControl = pending.any {
            val v = it.toInt() and 0xff
            v < 0x20 || v == 0x7f // 含退格/回车/方向键等 → 放弃整段
        }
        if (pending.size > maxPending || hasControl) {
            predicted = null
            pending = ByteArray(0)
            return false
        }

        // 从确认态重新预测：回看再大也只是 O(行数) 的 COW 分叉
        predicted = base.predictInput(pending)
        predictedAt = nowMs()
        return true
    }

    /** 当前应展示的状态：有预测显示预测，否则确认态。 */
    fun currentForDisplay(): ShadowTerminal? = predicted ?: confirmed

    /** 预测悬挂过久（服务器一直未确认/期间有输出）→ 丢弃并回确认态。 */
    fun glitchTimedOut(): Boolean = predicted != null && nowMs() - predictedAt > glitchTimeout
}

package dev.termish.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 连接状态语义色：状态点、banner、状态文案跨屏统一取色（勿再散落硬编码）。
 *
 * 仅覆盖「连接状态」语义；通知 channel 色与 OS logo 品牌色不在此列。
 */
object StatusColors {
    /** 已连接 / 成功。 */
    val Connected = Color(0xFF34C759)

    /** 进行中 / 警示：连接中、认证中、失联、降级提示。 */
    val Warning = Color(0xFFFFA726)

    /** 错误 / 失败：断开、认证失败、安装失败。 */
    val Error = Color(0xFFEF5350)

    /** 中性 / 未知：CLOSED / IDLE。 */
    val Neutral = Color(0xFF9E9E9E)
}

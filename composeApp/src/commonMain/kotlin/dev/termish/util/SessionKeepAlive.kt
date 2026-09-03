package dev.termish.util

/**
 * 会话保活钩子：SSH 连接建立/断开时调用。
 * Android 实现为前台服务 + PARTIAL_WAKE_LOCK，防止切后台被系统回收断连；
 * iOS / desktop 为空操作。
 */
enum class ForegroundSshRecovery {
    /** 保活机制正常，无需额外处理。 */
    KEEP,

    /** 保活机制中断，但 socket 可能仍健康；先探测，失败才重连。 */
    VERIFY,

    /** 平台挂起必然使 socket 失效；直接重建连接。 */
    REBUILD,
}

expect object SessionKeepAlive {
    fun onSessionStart(sessionId: String)

    fun onSessionEnd(sessionId: String)

    /**
     * 保活服务是否真的在运行。Android 前台服务被系统或厂商省电策略停掉后
     * 返回 false，供上层重新登记仍活跃的会话；
     * iOS / desktop 无保活服务，恒为 true（保持原行为）。
     */
    fun isActive(): Boolean

    /**
     * 回到前台时如何处理仍显示为已连接的 SSH socket。
     * iOS 后台挂起后直接重建；Android 仅在保活服务停止时先做端到端探测；
     * desktop 始终保持原连接。
     */
    fun foregroundSshRecovery(): ForegroundSshRecovery
}

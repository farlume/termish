package dev.termish.util

/**
 * 会话保活钩子：SSH 连接建立/断开时调用。
 * Android 实现为前台服务 + PARTIAL_WAKE_LOCK，防止切后台被系统回收断连；
 * iOS / desktop 为空操作。
 */
expect object SessionKeepAlive {
    fun onSessionStart()

    fun onSessionEnd()

    /**
     * 保活服务是否真的在运行。Android 前台服务被系统停掉（Android 15
     * dataSync 6h 超时、服务被杀）后返回 false，供上层重新拉起；
     * iOS / desktop 无保活服务，恒为 true（保持原行为）。
     */
    fun isActive(): Boolean

    /**
     * 回到前台时是否应主动重建仍显示为已连接的 SSH socket。
     * iOS 后台挂起后底层 socket 可能已经失效却尚未回调关闭；Android 仅在
     * 前台服务确实被系统停止时需要这样做；desktop 始终不需要。
     */
    fun requiresSshReconnectOnForeground(): Boolean
}

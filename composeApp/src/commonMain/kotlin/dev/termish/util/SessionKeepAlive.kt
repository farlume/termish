package dev.termish.util

/**
 * 会话保活钩子：SSH 连接建立/断开时调用。
 * Android 实现为前台服务 + PARTIAL_WAKE_LOCK，防止切后台被系统回收断连；
 * iOS / desktop 为空操作。
 */
expect object SessionKeepAlive {
    fun onSessionStart()
    fun onSessionEnd()
}

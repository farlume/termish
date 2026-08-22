package dev.termish.notify

/** 桌面暂无通知语义（后续可接系统 tray 通知）。 */
actual fun showPlatformNotification(
    id: Int,
    title: String,
    body: String,
    hostId: String?,
) {}

actual fun openNotificationSettings() {}

actual fun requestNotificationPermission() {}

package dev.termish.notify

import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNUserNotificationCenter

private val center: UNUserNotificationCenter get() = UNUserNotificationCenter.currentNotificationCenter()

/** iOS：本地通知（APNs 不需要——通知都来自 SSH 连接内事件）。 */
actual fun showPlatformNotification(id: Int, title: String, body: String, hostId: String?) {
    val content = UNMutableNotificationContent()
    content.setTitle(title)
    content.setBody(body)
    val request = UNNotificationRequest.requestWithIdentifier(
        "termish-$id",
        content,
        null,
    )
    center.addNotificationRequest(request, null)
}

/** 跳系统 App 设置页（iOS 通知设置在系统设置里）。 */
actual fun openNotificationSettings() {
    val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString)
    url?.let { UIApplication.sharedApplication.openURL(it, emptyMap<Any?, Any?>(), null) }
}

/** 请求通知权限（首次打开通知开关时调用）。 */
actual fun requestNotificationPermission() {
    center.requestAuthorizationWithOptions(
        UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge,
    ) { _, _ -> }
}

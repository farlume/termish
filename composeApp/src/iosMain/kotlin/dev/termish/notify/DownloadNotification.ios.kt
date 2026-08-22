package dev.termish.notify

import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNUserNotificationCenter

/** iOS：下载完成本地通知（openUri 忽略——iOS 下载走 Files 转存，无 content:// 打开动作）。 */
actual fun showDownloadDoneNotification(
    title: String,
    body: String,
    openUri: String?,
) {
    val content = UNMutableNotificationContent()
    content.setTitle(title)
    content.setBody(body)
    val request =
        UNNotificationRequest.requestWithIdentifier(
            "termish-download-${title.hashCode()}-${body.hashCode()}",
            content,
            null,
        )
    UNUserNotificationCenter.currentNotificationCenter().addNotificationRequest(request, null)
}

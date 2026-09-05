package dev.termish.notify

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNUserNotificationCenter
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

private val center: UNUserNotificationCenter get() = UNUserNotificationCenter.currentNotificationCenter()

/** iOS：本地通知（APNs 不需要——通知都来自 SSH 连接内事件）。 */
actual fun showPlatformNotification(
    id: Int,
    title: String,
    body: String,
    hostId: String?,
) {
    val content = UNMutableNotificationContent()
    content.setTitle(title)
    content.setBody(body)
    val request =
        UNNotificationRequest.requestWithIdentifier(
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

@Composable
actual fun rememberNotificationPermissionController(): NotificationPermissionController =
    remember {
        object : NotificationPermissionController {
            override fun refresh(onResult: (NotificationPermissionState) -> Unit) {
                center.getNotificationSettingsWithCompletionHandler { settings ->
                    val state =
                        when (settings?.authorizationStatus?.toInt()) {
                            1 -> NotificationPermissionState.DENIED
                            2, 3, 4 -> NotificationPermissionState.GRANTED
                            else -> NotificationPermissionState.UNKNOWN
                        }
                    dispatch_async(dispatch_get_main_queue()) { onResult(state) }
                }
            }

            override fun request(onResult: (NotificationPermissionState) -> Unit) {
                center.requestAuthorizationWithOptions(
                    UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge,
                ) { granted, _ ->
                    dispatch_async(dispatch_get_main_queue()) {
                        onResult(
                            if (granted) {
                                NotificationPermissionState.GRANTED
                            } else {
                                NotificationPermissionState.DENIED
                            },
                        )
                    }
                }
            }
        }
    }

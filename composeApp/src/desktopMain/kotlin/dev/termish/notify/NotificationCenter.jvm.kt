package dev.termish.notify

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** 桌面暂无通知语义（后续可接系统 tray 通知）。 */
actual fun showPlatformNotification(
    id: Int,
    title: String,
    body: String,
    hostId: String?,
) {}

actual fun openNotificationSettings() {}

@Composable
actual fun rememberNotificationPermissionController(): NotificationPermissionController =
    remember {
        object : NotificationPermissionController {
            override fun refresh(onResult: (NotificationPermissionState) -> Unit) {
                onResult(NotificationPermissionState.GRANTED)
            }

            override fun request(onResult: (NotificationPermissionState) -> Unit) {
                onResult(NotificationPermissionState.GRANTED)
            }
        }
    }

package dev.termish.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import dev.termish.AppContext
import dev.termish.MainActivity
import dev.termish.app.R

private const val CHANNEL_ID = "termish_session"
private const val EXTRA_HOST_ID = "termish_host_id"

/** Android：系统通知（前台服务已保活；通知权限 Android 13+ 需运行时请求）。 */
actual fun showPlatformNotification(
    id: Int,
    title: String,
    body: String,
    hostId: String?,
) {
    val context = AppContext.get()
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (Build.VERSION.SDK_INT >= 26) {
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "会话事件", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }
    // Android 13+ 未授权时静默放弃（设置页开关会引导授权）
    if (Build.VERSION.SDK_INT >= 33 &&
        context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
        android.content.pm.PackageManager.PERMISSION_GRANTED
    ) {
        return
    }

    // 点击通知：打开 App；hostId 携带时带「重新连接」动作
    val openIntent =
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
    val contentIntent =
        PendingIntent.getActivity(
            context,
            id,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    val builder =
        NotificationCompat
            .Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setColor(0xFF34D399.toInt()) // 品牌 emerald
            .setContentIntent(contentIntent)
    if (hostId != null) {
        val reconnectIntent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_HOST_ID, hostId)
            }
        val reconnectPending =
            PendingIntent.getActivity(
                context,
                id + 1000,
                reconnectIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        builder.addAction(0, "重新连接", reconnectPending)
    }
    nm.notify(id, builder.build())
}

/** 跳系统 App 通知设置页（Android 13+；低版本跳应用详情页）。 */
actual fun openNotificationSettings() {
    val context = AppContext.get()
    val intent =
        if (Build.VERSION.SDK_INT >= 26) {
            Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.parse("package:${context.packageName}"))
        }
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

/** 请求通知权限（Android 13+；从设置页打开通知开关时调用）。 */
actual fun requestNotificationPermission() {
    val activity = AppContext.currentActivity ?: return
    if (Build.VERSION.SDK_INT >= 33) {
        activity.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
    }
}

/** 通知动作转发：通知「重新连接」→ MainActivity.onNewIntent → 这里 → NotificationCenter 回调。 */
fun handleNotificationIntent(intent: Intent?) {
    val hostId = intent?.getStringExtra(EXTRA_HOST_ID) ?: return
    NotificationCenter.onReconnectRequest?.invoke(hostId)
}

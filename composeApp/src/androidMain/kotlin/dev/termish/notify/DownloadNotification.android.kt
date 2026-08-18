package dev.termish.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import dev.termish.AppContext
import dev.termish.app.R

private const val CHANNEL_ID = "termish_download"

/**
 * Android：下载完成通知（前台也发）。[openUri] 非空时点击用系统默认应用打开文件。
 * Android 13+ 未授权通知时静默放弃（与应用内进度条/snackbar 不冲突）。
 */
actual fun showDownloadDoneNotification(title: String, body: String, openUri: String?) {
    val context = AppContext.get()
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (Build.VERSION.SDK_INT >= 26) {
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "下载", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }
    if (Build.VERSION.SDK_INT >= 33 &&
        context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
        android.content.pm.PackageManager.PERMISSION_GRANTED
    ) {
        return
    }

    val builder = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle(title)
        .setContentText(body)
        .setAutoCancel(true)
        .setColor(0xFF34D399.toInt()) // 品牌 emerald

    if (openUri != null) {
        val open = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(openUri), "application/octet-stream")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val contentIntent = PendingIntent.getActivity(
            context, openUri.hashCode(), open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        builder.setContentIntent(contentIntent)
    }

    nm.notify(openUri?.hashCode() ?: body.hashCode(), builder.build())
}

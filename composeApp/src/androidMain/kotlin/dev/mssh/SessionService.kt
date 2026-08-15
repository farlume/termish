package dev.mssh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager

/**
 * SSH 会话前台服务：防止切后台时进程被系统回收导致连接断开。
 * 多个会话通过引用计数共享一个前台服务。
 */
class SessionService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            activeSessions = (activeSessions - 1).coerceAtLeast(0)
            if (activeSessions == 0) stopSelf()
            return START_NOT_STICKY
        }
        activeSessions++
        startForeground(NOTIF_ID, buildNotification())
        acquireWakeLock()
        return START_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        activeSessions = 0
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mssh:session").apply {
            setReferenceCounted(false)
            // 兜底 12 小时，正常随服务 onDestroy 释放
            acquire(12 * 60 * 60 * 1000L)
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "SSH 会话", NotificationManager.IMPORTANCE_LOW),
        )
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("MSSH 会话进行中")
            .setContentText("保持 SSH 连接在后台存活")
            .setSmallIcon(applicationInfo.icon)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "session"
        private const val NOTIF_ID = 1
        private const val ACTION_STOP = "dev.mssh.SESSION_STOP"

        @Volatile
        private var activeSessions = 0

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, SessionService::class.java))
        }

        fun stop(ctx: Context) {
            if (activeSessions <= 0) return
            ctx.startService(Intent(ctx, SessionService::class.java).setAction(ACTION_STOP))
        }
    }
}

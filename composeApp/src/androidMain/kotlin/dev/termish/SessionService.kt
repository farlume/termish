package dev.termish

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log

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
            Log.i(TAG, "stop: activeSessions=$activeSessions")
            if (activeSessions == 0) {
                releaseWakeLock()
                stopSelf()
            }
            return START_NOT_STICKY
        }
        if (intent == null) {
            // START_STICKY 重启：进程被杀后计数已归零，没有活跃会话就不该残留
            // 一个空转的前台服务 + wakelock；进程活着但服务被杀时计数仍有效，正常恢复。
            if (activeSessions <= 0) {
                Log.w(TAG, "restarted with no active sessions, stopping")
                stopSelf()
                return START_NOT_STICKY
            }
            Log.i(TAG, "restarted by system, activeSessions=$activeSessions")
        } else {
            activeSessions++
            Log.i(TAG, "start: activeSessions=$activeSessions")
        }
        startForegroundCompat()
        acquireWakeLock()
        return START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15：dataSync 前台服务有 6 小时上限，超时后系统调用这里。
        // 保活到此为止，释放锁并退出；用户回前台时由生命周期钩子自动重连。
        Log.w(TAG, "foreground service timed out (Android 15 dataSync 6h limit)")
        releaseWakeLock()
        stopSelf()
    }

    override fun onDestroy() {
        releaseWakeLock()
        activeSessions = 0
        Log.i(TAG, "destroyed")
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "termish:session").apply {
            setReferenceCounted(false)
            // 兜底 12 小时，正常随服务 onDestroy 释放
            acquire(12 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
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
            .setContentTitle("Termish 会话进行中")
            .setContentText("保持 SSH 连接在后台存活")
            .setSmallIcon(applicationInfo.icon)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "Termish-Service"
        private const val CHANNEL_ID = "session"
        private const val NOTIF_ID = 1
        private const val ACTION_STOP = "dev.termish.SESSION_STOP"

        @Volatile
        private var activeSessions = 0

        fun start(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, SessionService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "startForegroundService failed", e)
            }
        }

        fun stop(ctx: Context) {
            if (activeSessions <= 0) return
            try {
                ctx.startService(Intent(ctx, SessionService::class.java).setAction(ACTION_STOP))
            } catch (e: Exception) {
                Log.e(TAG, "stop via startService failed", e)
            }
        }
    }
}

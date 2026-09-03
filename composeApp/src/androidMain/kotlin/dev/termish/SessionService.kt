package dev.termish

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * SSH 会话前台服务：防止切后台时进程被系统回收导致连接断开。
 * 多个会话按 sessionId 幂等登记，共享一个前台服务。
 */
class SessionService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val handler = Handler(Looper.getMainLooper())

    /**
     * 下次续期时刻（elapsedRealtime 单调钟）。只由 [scheduleRenew] 到点推进；
     * 后续会话 start 只补排班、不重置计时——否则新会话会推迟旧会话的续期，
     * 让先启动会话的 wakelock 在 12h 超时后无人续期（保活空洞）。
     */
    private var nextRenewAt = 0L

    /** wakelock 兜底超时续期：有活跃会话时按 [nextRenewAt] 刷新 12h 超时。 */
    private val renewWakeLock =
        object : Runnable {
            override fun run() {
                if (desiredSessionIds.isNotEmpty()) scheduleRenew()
            }
        }

    /** 按 [nextRenewAt] 安排续期；已到点则立即续期并推进时刻（幂等，可随时调用）。 */
    private fun scheduleRenew() {
        val now = SystemClock.elapsedRealtime()
        if (nextRenewAt <= now) {
            releaseWakeLock()
            acquireWakeLock()
            nextRenewAt = now + RENEW_INTERVAL_MS
        }
        handler.removeCallbacks(renewWakeLock)
        handler.postDelayed(renewWakeLock, (nextRenewAt - now).coerceAtLeast(1))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID)
        Log.i(TAG, "sync: action=${intent?.action} session=$sessionId activeSessions=${desiredSessionIds.size}")
        // 以调用方已同步更新的目标集合为准，而不是按 Intent 到达顺序增减计数。
        // 同一 session 在重连时会紧邻发送 stop/start；即使旧 stop 最后才被处理，
        // 只要新连接仍在目标集合里，就绝不能把服务停掉。
        if (desiredSessionIds.isEmpty()) {
            releaseKeepAliveLocks()
            handler.removeCallbacks(renewWakeLock)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (intent == null) {
            Log.i(TAG, "restarted by system, activeSessions=${desiredSessionIds.size}")
        }
        startForegroundCompat()
        isRunning = true
        acquireWakeLock()
        acquireWifiLock()
        scheduleRenew()
        return START_STICKY
    }

    override fun onTimeout(
        startId: Int,
        fgsType: Int,
    ) {
        // 防御性处理：若系统对当前前台服务类型触发超时，立即释放资源。
        // 用户回前台时会重新登记会话，并先验证 SSH 健康状态。
        Log.w(TAG, "foreground service timed out type=$fgsType")
        releaseKeepAliveLocks()
        handler.removeCallbacks(renewWakeLock)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(renewWakeLock)
        releaseKeepAliveLocks()
        nextRenewAt = 0
        isRunning = false
        Log.i(TAG, "destroyed")
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock =
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "termish:session").apply {
                setReferenceCounted(false)
                // 兜底 12 小时，正常随服务 onDestroy 释放
                acquire(12 * 60 * 60 * 1000L)
            }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    /**
     * SSH 是持续、低流量的长连接：CPU 唤醒锁不能阻止部分 ROM 在 App 退后台后
     * 让 Wi-Fi 射频休眠。会话存在期间同时持有 WifiLock，避免 socket 因打开其他
     * App 数秒就被底层网络回收；前台服务通知让这项电量开销对用户可见。
     */
    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        wifiLock =
            wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "termish:ssh-wifi").apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun releaseWifiLock() {
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    private fun releaseKeepAliveLocks() {
        releaseWakeLock()
        releaseWifiLock()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "SSH 会话", NotificationManager.IMPORTANCE_LOW),
        )
        val openIntent =
            PendingIntent.getActivity(
                this,
                0,
                packageManager.getLaunchIntentForPackage(packageName),
                PendingIntent.FLAG_IMMUTABLE,
            )
        return Notification
            .Builder(this, CHANNEL_ID)
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
        private const val EXTRA_SESSION_ID = "session_id"
        private const val RENEW_INTERVAL_MS = 8 * 60 * 60 * 1000L

        /**
         * 活跃会话登记：按 sessionId 幂等增删，避免同一会话重复 start 导致引用计数
         * 泄漏，或重连期间交错 start/stop 让服务提前退出。
         */
        private val desiredSessionIds = ConcurrentHashMap.newKeySet<String>()

        /**
         * 前台服务是否真的在运行。服务被系统或厂商省电策略停掉后为 false，
         * 上层据此重新登记仍活跃的会话。
         */
        @Volatile
        var isRunning = false
            private set

        fun start(
            ctx: Context,
            sessionId: String,
        ) {
            if (!desiredSessionIds.add(sessionId) && isRunning) return
            try {
                ctx.startForegroundService(
                    Intent(ctx, SessionService::class.java).putExtra(EXTRA_SESSION_ID, sessionId),
                )
            } catch (e: Exception) {
                // 保留目标登记：当前后台限制可能是暂时的，回到前台时会再次同步。
                Log.e(TAG, "startForegroundService failed", e)
            }
        }

        fun stop(
            ctx: Context,
            sessionId: String,
        ) {
            if (!desiredSessionIds.remove(sessionId)) return
            try {
                ctx.startService(
                    Intent(ctx, SessionService::class.java)
                        .setAction(ACTION_STOP)
                        .putExtra(EXTRA_SESSION_ID, sessionId),
                )
            } catch (e: Exception) {
                Log.e(TAG, "stop via startService failed", e)
            }
        }
    }
}

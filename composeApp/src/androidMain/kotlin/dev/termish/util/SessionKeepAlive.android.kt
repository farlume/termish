package dev.termish.util

import android.util.Log
import dev.termish.AppContext
import dev.termish.SessionService

actual object SessionKeepAlive {
    actual fun onSessionStart(sessionId: String) {
        try {
            SessionService.start(AppContext.get(), sessionId)
        } catch (e: Exception) {
            // 前台服务启动失败（如后台限制）不阻断连接，但必须留痕以便排查
            Log.e("Termish-SessionKeepAlive", "onSessionStart failed", e)
        }
    }

    actual fun onSessionEnd(sessionId: String) {
        try {
            SessionService.stop(AppContext.get(), sessionId)
        } catch (e: Exception) {
            Log.e("Termish-SessionKeepAlive", "onSessionEnd failed", e)
        }
    }

    actual fun isActive(): Boolean = SessionService.isRunning

    actual fun foregroundSshRecovery(): ForegroundSshRecovery = if (SessionService.isRunning) ForegroundSshRecovery.KEEP else ForegroundSshRecovery.VERIFY
}

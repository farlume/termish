package dev.mssh.util

import dev.mssh.AppContext
import dev.mssh.SessionService

actual object SessionKeepAlive {
    actual fun onSessionStart() {
        try {
            SessionService.start(AppContext.get())
        } catch (_: Exception) {
            // 前台服务启动失败（如后台限制）不阻断连接
        }
    }

    actual fun onSessionEnd() {
        try {
            SessionService.stop(AppContext.get())
        } catch (_: Exception) {
        }
    }
}

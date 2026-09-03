package dev.termish.util

actual object SessionKeepAlive {
    actual fun onSessionStart(sessionId: String) {}

    actual fun onSessionEnd(sessionId: String) {}

    actual fun isActive(): Boolean = true

    actual fun foregroundSshRecovery(): ForegroundSshRecovery = ForegroundSshRecovery.KEEP
}

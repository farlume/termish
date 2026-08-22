package dev.termish.util

actual object SessionKeepAlive {
    actual fun onSessionStart() {}

    actual fun onSessionEnd() {}

    actual fun isActive(): Boolean = true
}

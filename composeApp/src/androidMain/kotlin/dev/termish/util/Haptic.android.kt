package dev.termish.util

import android.view.HapticFeedbackConstants
import dev.termish.AppContext

/** Android：窗口轻击反馈（KEYBOARD_TAP，系统级、无需 VIBRATE 权限）。 */
actual fun hapticTick() {
    runCatching {
        val activity = AppContext.currentActivity ?: return
        activity.window.decorView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }
}

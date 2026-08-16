package dev.termish.util

import android.app.Activity
import android.app.Application
import android.os.Bundle
import dev.termish.AppContext

/**
 * Android：用 Activity 前后台计数实现生命周期钩子，回前台时触发
 * sessionManager.reconnectDroppedSessions()（与 iOS 行为一致）。
 * 保活失败/网络掐断导致的断连，回到前台即可自动重连恢复现场。
 */
actual fun observeAppLifecycle(listener: (foreground: Boolean) -> Unit): () -> Unit {
    val app = try {
        AppContext.get() as? Application
    } catch (_: Throwable) {
        null
    } ?: return {}

    var started = 0
    val callback = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            if (started == 0) listener(true)
            started++
        }

        override fun onActivityStopped(activity: Activity) {
            started--
            if (started == 0) listener(false)
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }
    app.registerActivityLifecycleCallbacks(callback)
    return { app.unregisterActivityLifecycleCallbacks(callback) }
}

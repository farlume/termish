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
    val app =
        try {
            AppContext.get() as? Application
        } catch (_: Throwable) {
            null
        } ?: return {}

    // 注册时 MainActivity 已在 STARTED 状态（AppRoot 组合发生在 Activity 启动后），
    // 而 registerActivityLifecycleCallbacks 不会为已存在的 Activity 补发
    // onActivityStarted——started 从 0 开始会让首次 onStopped 减成 -1，
    // listener(false) 永不触发（退后台持久化/回前台重连全部失效）。
    // 补偿初始计数为 1。
    var started = 1
    val callback =
        object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (started == 0) listener(true)
                started++
            }

            override fun onActivityStopped(activity: Activity) {
                started--
                if (started == 0) listener(false)
            }

            override fun onActivityCreated(
                activity: Activity,
                savedInstanceState: Bundle?,
            ) {}

            override fun onActivityResumed(activity: Activity) {}

            override fun onActivityPaused(activity: Activity) {}

            override fun onActivitySaveInstanceState(
                activity: Activity,
                outState: Bundle,
            ) {}

            override fun onActivityDestroyed(activity: Activity) {}
        }
    app.registerActivityLifecycleCallbacks(callback)
    return { app.unregisterActivityLifecycleCallbacks(callback) }
}

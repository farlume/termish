package dev.termish.util

import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillEnterForegroundNotification

/**
 * iOS：监听系统前后台通知。退到桌面（挂起）时记录活跃会话，
 * 回到前台时触发自动重连——socket 已被系统掐断，但服务端 herdr/tmux
 * 会话还在，重连后现场原样恢复。
 */
actual fun observeAppLifecycle(listener: (foreground: Boolean) -> Unit): () -> Unit {
    val center = NSNotificationCenter.defaultCenter
    val backgroundObserver = center.addObserverForName(
        UIApplicationDidEnterBackgroundNotification,
        null,
        null,
    ) { _ -> listener(false) }
    val foregroundObserver = center.addObserverForName(
        UIApplicationWillEnterForegroundNotification,
        null,
        null,
    ) { _ -> listener(true) }
    return {
        center.removeObserver(backgroundObserver)
        center.removeObserver(foregroundObserver)
    }
}

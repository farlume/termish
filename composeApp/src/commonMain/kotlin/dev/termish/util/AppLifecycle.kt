package dev.termish.util

/**
 * 平台前后台生命周期观察。
 *
 * 目前只有 iOS 真正接入：退到桌面后系统会挂起进程、掐断 SSH socket，
 * 回前台时据此自动重连活跃会话（缓冲保留）。Android 由前台服务 + wakelock
 * 保活、桌面端无后台语义，这两处的实现为空操作。
 *
 * 返回反注册函数（空操作实现返回空 lambda）。
 */
expect fun observeAppLifecycle(listener: (foreground: Boolean) -> Unit): () -> Unit

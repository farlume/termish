package dev.mssh.util

/** Android：前台服务 + wakelock 已保证后台存活，无需额外生命周期钩子。 */
actual fun observeAppLifecycle(listener: (foreground: Boolean) -> Unit): () -> Unit = {}

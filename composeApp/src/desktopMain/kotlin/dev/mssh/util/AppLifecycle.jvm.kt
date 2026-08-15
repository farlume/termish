package dev.mssh.util

/** 桌面端无后台语义（关闭窗口即退出）。 */
actual fun observeAppLifecycle(listener: (foreground: Boolean) -> Unit): () -> Unit = {}

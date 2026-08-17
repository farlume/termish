package dev.termish.util

import platform.Foundation.NSLog

/** iOS：Kotlin/Native 调试构建符号（release 自动关闭）。 */
actual val termLogEnabled: Boolean = kotlin.native.Platform.isDebugBinary

actual fun platformLog(level: Char, tag: String, msg: String) {
    NSLog("[%c/%s] %@", level, tag, msg)
}

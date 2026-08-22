package dev.termish

import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSTemporaryDirectory
import platform.UIKit.UIViewController
import platform.posix.O_APPEND
import platform.posix.O_CREAT
import platform.posix.O_WRONLY
import platform.posix.close
import platform.posix.open
import platform.posix.write

@OptIn(kotlin.experimental.ExperimentalNativeApi::class, ExperimentalForeignApi::class)
private fun installUncaughtHook() {
    kotlin.native.setUnhandledExceptionHook { throwable ->
        try {
            val text =
                buildString {
                    append("=== Termish-DIAG ")
                        .append(
                            kotlinx.datetime.Clock.System
                                .now(),
                        ).append(" ===\n")
                    append(throwable.toString()).append("\n")
                    append(throwable.stackTraceToString()).append("\n")
                }
            val base = NSTemporaryDirectory() ?: "/tmp"
            val path = "$base/termish-diag.log"
            val fd = open(path, O_CREAT or O_WRONLY or O_APPEND, 0x1A4u)
            if (fd >= 0) {
                val bytes = text.encodeToByteArray()
                bytes.usePinned { pinned ->
                    var off = 0
                    while (off < bytes.size) {
                        val n = write(fd, pinned.addressOf(off), (bytes.size - off).toULong())
                        if (n <= 0L) break
                        off += n.toInt()
                    }
                }
                close(fd)
            }
        } catch (_: Throwable) {
        }
    }
}

@Suppress("ktlint:standard:function-naming") // Swift 互操作入口（MainViewControllerKt.MainViewController()）
fun MainViewController(): UIViewController {
    installUncaughtHook()
    return ComposeUIViewController { App() }
}

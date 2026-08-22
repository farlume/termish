package dev.termish.ui

import androidx.compose.runtime.Composable
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UISceneActivationStateForegroundActive
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.O_CREAT
import platform.posix.O_TRUNC
import platform.posix.O_WRONLY
import platform.posix.close
import platform.posix.open
import platform.posix.write

/**
 * iOS：下载先写入临时文件，[FileSink.close] 时弹出 UIDocumentPicker
 * （"存储到文件"，用户可转存 Files/其他 App）。min target 15.0，用 iOS 14+ 的
 * forExporting API。
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun rememberFileSaver(onReady: (name: String, sink: FileSink) -> Unit): (name: String) -> Unit =
    { name ->
        val tmpDir = NSTemporaryDirectory()
        if (tmpDir != null) {
            val path = "$tmpDir/$name"
            val fd = open(path, O_CREAT or O_WRONLY or O_TRUNC, 0x1A4u)
            if (fd >= 0) {
                onReady(
                    name,
                    object : FileSink {
                        override fun write(bytes: ByteArray) {
                            bytes.usePinned { pinned ->
                                var off = 0
                                while (off < bytes.size) {
                                    val n = write(fd, pinned.addressOf(off), (bytes.size - off).toULong())
                                    if (n <= 0L) break
                                    off += n.toInt()
                                }
                            }
                        }

                        override fun close() {
                            close(fd)
                            val url = NSURL.fileURLWithPath(path)
                            val picker = UIDocumentPickerViewController(forExportingURLs = listOf(url), asCopy = true)
                            dispatch_async(dispatch_get_main_queue()) {
                                topViewController()?.presentViewController(picker, animated = true, completion = null)
                            }
                        }
                    },
                )
            }
        }
    }

@OptIn(ExperimentalForeignApi::class)
private fun topViewController(): UIViewController? {
    val window =
        UIApplication.sharedApplication.connectedScenes
            .filterIsInstance<UIWindowScene>()
            .firstOrNull { it.activationState == UISceneActivationStateForegroundActive }
            ?.windows
            ?.filterIsInstance<UIWindow>()
            ?.firstOrNull { it.isKeyWindow() }
    return window?.rootViewController
}

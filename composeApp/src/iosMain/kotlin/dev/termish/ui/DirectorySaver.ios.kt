package dev.termish.ui

import androidx.compose.runtime.Composable
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UIKit.UISceneActivationStateForegroundActive
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.O_CREAT
import platform.posix.O_TRUNC
import platform.posix.O_WRONLY
import platform.posix.close
import platform.posix.open
import platform.posix.write

/**
 * iOS：递归下载到临时目录，[DirectorySink.close] 时用 UIDocumentPicker
 * 导出整个目录（用户可存到 Files/其他 App）。
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun rememberDirectorySaver(onReady: (name: String, sink: DirectorySink) -> Unit): (name: String) -> Unit {
    return { name ->
        val tmpDir = NSTemporaryDirectory()
        if (tmpDir != null) {
            val safeName = sanitizeDirName(name)
            val root = "$tmpDir/termish-dir-$safeName"
            // 清掉上次失败/中断残留的同名目录，避免陈旧文件混入本次导出（O_TRUNC 只覆盖同名文件）
            NSFileManager.defaultManager.removeItemAtPath(root, null)
            NSFileManager.defaultManager.createDirectoryAtPath(root, true, null, null)
            onReady(name, object : DirectorySink {
                override fun openFile(relativePath: String): FileSink {
                    val path = "$root/$relativePath"
                    val parent = path.substringBeforeLast('/')
                    NSFileManager.defaultManager.createDirectoryAtPath(parent, true, null, null)
                    val fd = open(path, O_CREAT or O_WRONLY or O_TRUNC, 0x1A4u)
                    if (fd < 0) throw IllegalStateException("无法写入: $relativePath")
                    return object : FileSink {
                        override fun write(bytes: ByteArray) {
                            bytes.usePinned { pinned ->
                                var off = 0
                                while (off < bytes.size) {
                                    val n = write(fd, pinned.addressOf(off), (bytes.size - off).toULong())
                                    // 写失败/写 0 字节必须抛错：静默截断会让上层误报下载成功
                                    if (n < 0) throw IllegalStateException("写入失败: $relativePath")
                                    if (n == 0L) throw IllegalStateException("写入中断: $relativePath")
                                    off += n.toInt()
                                }
                            }
                        }

                        override fun close() {
                            close(fd)
                        }
                    }
                }

                override fun close() {
                    val url = NSURL.fileURLWithPath(root)
                    val picker = UIDocumentPickerViewController(forExportingURLs = listOf(url), asCopy = true)
                    dispatch_async(dispatch_get_main_queue()) {
                        topViewController()?.presentViewController(picker, animated = true, completion = null)
                    }
                }
            })
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun topViewController(): UIViewController? {
    val window = UIApplication.sharedApplication.connectedScenes
        .filterIsInstance<UIWindowScene>()
        .firstOrNull { it.activationState == UISceneActivationStateForegroundActive }
        ?.windows
        ?.filterIsInstance<UIWindow>()
        ?.firstOrNull { it.isKeyWindow() }
    return window?.rootViewController
}

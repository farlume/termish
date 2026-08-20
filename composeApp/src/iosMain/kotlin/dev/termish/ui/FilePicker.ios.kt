package dev.termish.ui

import androidx.compose.runtime.Composable
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import platform.Foundation.NSFileCoordinator
import platform.Foundation.NSFileCoordinatorReadingForUploading
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.UniformTypeIdentifiers.UTTypeData
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UIKit.UISceneActivationStateForegroundActive
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.O_RDONLY
import platform.posix.close
import platform.posix.open
import platform.posix.read
import platform.posix.stat

private const val CHUNK = 64 * 1024

/**
 * iOS：UIDocumentPicker（import 模式）选文件上传——对齐 Android 的
 * OpenDocument 流式读：安全作用域 URL 先经 NSFileCoordinator 拷到临时目录，
 * 再 POSIX fd 分块读。内存峰值 = 单块 64KB，任意大小文件不整体驻堆；
 * readChunk 到 EOF 时关闭 fd，临时文件由系统按需清理（NSTemporaryDirectory）。
 *
 * 协调回调内完成拷贝 + 打开 fd + 回调 onPicked（回调返回即数据就绪，
 * 不依赖 coordinateReading 的同步/异步方言）；onPicked 投递上传协程，
 * 后续 readChunk 逐块拉取。
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun rememberFilePicker(
    onPicked: (PickedFile) -> Unit,
): () -> Unit = {
    val picker = UIDocumentPickerViewController(
        forOpeningContentTypes = listOf(UTTypeData),
    )
    picker.allowsMultipleSelection = true
    picker.delegate = object : NSObject(), UIDocumentPickerDelegateProtocol {
        override fun documentPicker(
            controller: UIDocumentPickerViewController,
            didPickDocumentsAtURLs: List<*>,
        ) {
            // 多选：每个 URL 独立走安全作用域 + 协调拷贝 + fd 流式读，逐文件回调
            didPickDocumentsAtURLs.filterIsInstance<NSURL>().forEach { url ->
                val name = url.lastPathComponent ?: "file"
                val coordinator = NSFileCoordinator()
                // 安全作用域 URL：document picker 返回的 URL 必须先申请访问权，
                // 再经 NSFileCoordinator 协调拷贝（reading-for-uploading 语义下
                // 被占用中的文件也可读）；访问权与拷贝完成成对释放。
                val scoped = url.startAccessingSecurityScopedResource()
                try {
                    coordinator.coordinateReadingItemAtURL(url, NSFileCoordinatorReadingForUploading, null) { newUrl ->
                        val src = newUrl ?: return@coordinateReadingItemAtURL
                        val tmp = NSTemporaryDirectory() ?: return@coordinateReadingItemAtURL
                        val dst = "${tmp}termish-upload-$name"
                        NSFileManager.defaultManager.removeItemAtPath(dst, null)
                        if (!NSFileManager.defaultManager.copyItemAtURL(src, NSURL.fileURLWithPath(dst), null)) {
                            return@coordinateReadingItemAtURL
                        }
                        var size = 0L
                        memScoped {
                            val st = alloc<stat>()
                            if (stat(dst, st.ptr) == 0) size = st.st_size
                        }
                        val fd = open(dst, O_RDONLY)
                        if (fd < 0) return@coordinateReadingItemAtURL
                        onPicked(
                            PickedFile(name, size) {
                                val buf = ByteArray(CHUNK)
                                val n = buf.usePinned { pinned ->
                                    read(fd, pinned.addressOf(0), CHUNK.toULong())
                                }
                                if (n <= 0L) {
                                    close(fd)
                                    null
                                } else {
                                    buf.copyOf(n.toInt())
                                }
                            },
                        )
                    }
                } finally {
                    if (scoped) url.stopAccessingSecurityScopedResource()
                }
            }
        }
    }
    dispatch_async(dispatch_get_main_queue()) {
        topViewController()?.presentViewController(picker, animated = true, completion = null)
    }
}

/** 顶层 VC（FileSaver.ios.kt 同款逻辑，独立一份避免跨文件私有可见性）。 */
private fun topViewController(): UIViewController? {
    val window = UIApplication.sharedApplication.connectedScenes
        .filterIsInstance<UIWindowScene>()
        .firstOrNull { it.activationState == UISceneActivationStateForegroundActive }
        ?.windows
        ?.filterIsInstance<UIWindow>()
        ?.firstOrNull { it.isKeyWindow() }
    return window?.rootViewController
}

@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.termish.util

import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSLog
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication

/** iOS：Kotlin/Native 调试构建符号（release 自动关闭）。 */
actual val termLogEnabled: Boolean = kotlin.native.Platform.isDebugBinary

actual fun platformLog(level: Char, tag: String, msg: String) {
    // K/N 的 NSLog vararg 不会把 Kotlin String 桥接为 NSString：%@ 会拿到字符串内部数据指针并崩溃。
    // 用 %s + C 字符串指针，避开 ObjC 对象桥接。
    memScoped {
        NSLog("[%s/%s] %s", level.toString().cstr.ptr, tag.cstr.ptr, msg.cstr.ptr)
    }
}

/** 日志目录：Documents/logs（iTunes 文件共享可见，方便导出）。 */
actual fun logFileDirectory(): String? {
    val docs = NSFileManager.defaultManager.URLsForDirectory(
        NSDocumentDirectory, NSUserDomainMask,
    ).firstOrNull() as? NSURL
    val dir = docs?.path + "/logs"
    NSFileManager.defaultManager.createDirectoryAtPath(dir, true, null, null)
    return dir
}

/** iOS：UIActivityViewController 分享日志文件。 */
actual fun shareDiagnosticLogs() {
    val dir = logFileDirectory() ?: return
    val url = NSURL.fileURLWithPath("$dir/termish.log")
    val vc = UIActivityViewController(listOf(url), null)
    UIApplication.sharedApplication.keyWindow?.rootViewController
        ?.presentViewController(vc, true, null)
}

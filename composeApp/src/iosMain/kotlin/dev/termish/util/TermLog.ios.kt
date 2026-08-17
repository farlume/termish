package dev.termish.util

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
    NSLog("[%c/%s] %@", level, tag, msg)
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

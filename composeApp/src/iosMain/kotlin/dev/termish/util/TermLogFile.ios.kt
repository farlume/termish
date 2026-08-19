@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.termish.util

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSFileSize
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite

/** iOS：NSFileManager + POSIX 追加写（commonMain 不可用 java.io.File）。 */
internal actual class TermLogFile actual constructor(private val path: String) {

    actual fun exists(): Boolean = NSFileManager.defaultManager.fileExistsAtPath(path)

    actual fun length(): Long =
        (NSFileManager.defaultManager.attributesOfItemAtPath(path, null)
            ?.get(NSFileSize) as? NSNumber)?.longLongValue ?: 0L

    actual fun delete() {
        NSFileManager.defaultManager.removeItemAtPath(path, null)
    }

    actual fun renameTo(target: String): Boolean =
        NSFileManager.defaultManager.moveItemAtPath(path, toPath = target, error = null)

    actual fun appendText(text: String) {
        // "ab"：追加 + 不存在时创建；fwrite 一次性写（日志行小，无需分块）
        val fd = fopen(path, "ab") ?: return
        try {
            val bytes = text.encodeToByteArray()
            bytes.usePinned { pinned ->
                fwrite(pinned.addressOf(0), 1u, bytes.size.toULong(), fd)
            }
        } finally {
            fclose(fd)
        }
    }

    actual fun absolutePath(): String = path
}

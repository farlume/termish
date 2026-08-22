package dev.termish.util

/**
 * 跨平台日志文件句柄（诊断日志 1MB 轮转用）：
 * commonMain 不能直接引用 java.io.File（Kotlin/Native 无 JVM），
 * 平台实现：Android/desktop = java.io.File；iOS = NSFileManager/NSFileHandle。
 */
internal expect class TermLogFile(
    path: String,
) {
    fun exists(): Boolean

    fun length(): Long

    fun delete()

    /** 重命名（覆盖目标不存在的前提）。 */
    fun renameTo(target: String): Boolean

    /** 追加写（文件不存在时创建）。 */
    fun appendText(text: String)

    fun absolutePath(): String
}

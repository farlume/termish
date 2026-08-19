package dev.termish.util

import java.io.File

/** JVM（Android/desktop）：java.io.File 委托。 */
internal actual class TermLogFile actual constructor(private val path: String) {
    private val file = File(path)

    actual fun exists(): Boolean = file.exists()

    actual fun length(): Long = file.length()

    actual fun delete() {
        file.delete()
    }

    actual fun renameTo(target: String): Boolean = file.renameTo(File(target))

    actual fun appendText(text: String) {
        file.appendText(text)
    }

    actual fun absolutePath(): String = file.absolutePath
}

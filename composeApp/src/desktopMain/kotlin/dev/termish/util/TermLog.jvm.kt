package dev.termish.util

import java.io.File

/** 桌面：debug 构建才输出（gradle 属性控制，release 打包关闭）。 */
actual val termLogEnabled: Boolean = System.getProperty("termish.log") == "true" || System.getenv("TERMISH_LOG") == "1"

actual fun platformLog(level: Char, tag: String, msg: String) {
    println("[$level/$tag] $msg")
}

/** 日志目录：~/.termish/logs。 */
actual fun logFileDirectory(): String? = runCatching {
    File(System.getProperty("user.home"), ".termish/logs").apply { mkdirs() }.absolutePath
}.getOrNull()

/** 桌面：提示日志路径。 */
actual fun shareDiagnosticLogs() {
    println("诊断日志：${logFileDirectory()}/termish.log")
}

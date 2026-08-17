package dev.termish.util

/** 桌面：debug 构建才输出（gradle 属性控制，release 打包关闭）。 */
actual val termLogEnabled: Boolean = System.getProperty("termish.log") == "true" || System.getenv("TERMISH_LOG") == "1"

actual fun platformLog(level: Char, tag: String, msg: String) {
    println("[$level/$tag] $msg")
}

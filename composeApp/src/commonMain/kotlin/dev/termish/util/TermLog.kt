package dev.termish.util

/**
 * 统一日志层。全链路打点（连接/重连/网络/通知/生命周期），
 * 调试定位问题不再需要临时 println。
 *
 * - [termLogEnabled] 平台按构建类型控制：Android debuggable、
 *   iOS DEBUG 符号、桌面 debug 构建
 * - [diagnosticsEnabled] 诊断模式：release 也可运行时开启（设置页），
 *   同时写日志文件（[logFileDirectory]），供导出排查线上问题
 * - 消息为惰性参数：开关关闭时 lambda 不执行，字符串拼接零开销
 * - 用法：TermLog.i("ssh") { "连接开始 $host:$port" }
 */
object TermLog {

    /** 诊断模式（设置页开关）：release 也打点并写文件。 */
    @Volatile
    var diagnosticsEnabled: Boolean = false

    /** 是否输出（构建调试 或 诊断模式）。 */
    val enabled: Boolean get() = termLogEnabled || diagnosticsEnabled

    /** 当前日志文件路径（诊断模式开启且文件输出可用时非空）。 */
    val logFilePath: String? get() = if (diagnosticsEnabled) _logFile else null

    fun d(tag: String, msg: () -> String) = log('D', tag, msg)
    fun i(tag: String, msg: () -> String) = log('I', tag, msg)
    fun w(tag: String, msg: () -> String) = log('W', tag, msg)
    fun e(tag: String, msg: () -> String) = log('E', tag, msg)

    @Volatile
    private var _logFile: String? = null

    private fun log(level: Char, tag: String, msg: () -> String) {
        if (!enabled) return
        val text = msg()
        platformLog(level, tag, text)
        if (diagnosticsEnabled) writeFile(level, tag, text)
    }

    /** 追加写日志文件（1MB 轮转：termish.log → termish.log.old）。失败静默。 */
    private fun writeFile(level: Char, tag: String, msg: String) {
        try {
            val dir = logFileDirectory() ?: return
            val file = java.io.File(dir, "termish.log")
            if (file.length() > 1024 * 1024) {
                java.io.File(dir, "termish.log.old").let {
                    if (it.exists()) it.delete()
                }
                file.renameTo(java.io.File(dir, "termish.log.old"))
            }
            file.appendText("[${level}/${tag}] $msg\n")
            _logFile = file.absolutePath
        } catch (_: Throwable) {
        }
    }
}

/** 平台构建是否为调试（release 关闭打点）。 */
expect val termLogEnabled: Boolean

/** 平台日志输出：Android logcat / iOS NSLog / 桌面 stdout。 */
expect fun platformLog(level: Char, tag: String, msg: String)

/** 日志文件目录（诊断模式写文件用）；不可用时返回 null。 */
expect fun logFileDirectory(): String?

/** 分享/导出诊断日志（Android 系统分享 / iOS UIActivityViewController / 桌面提示路径）。 */
expect fun shareDiagnosticLogs()

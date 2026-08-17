package dev.termish.util

/**
 * 统一日志层。全链路打点（连接/重连/网络/通知/生命周期），
 * 调试定位问题不再需要临时 println。
 *
 * - [termLogEnabled] 平台按构建类型控制：Android BuildConfig.DEBUG、
 *   iOS DEBUG 符号、桌面 debug 构建——release 全关
 * - 消息为惰性参数：开关关闭时 lambda 不执行，字符串拼接零开销
 * - 用法：TermLog.i("ssh") { "连接开始 $host:$port" }
 */
object TermLog {

    fun d(tag: String, msg: () -> String) = log('D', tag, msg)
    fun i(tag: String, msg: () -> String) = log('I', tag, msg)
    fun w(tag: String, msg: () -> String) = log('W', tag, msg)
    fun e(tag: String, msg: () -> String) = log('E', tag, msg)

    private inline fun log(level: Char, tag: String, msg: () -> String) {
        if (termLogEnabled) platformLog(level, tag, msg())
    }
}

/** 平台构建是否为调试（release 关闭打点）。 */
expect val termLogEnabled: Boolean

/** 平台日志输出：Android logcat / iOS NSLog / 桌面 stdout。 */
expect fun platformLog(level: Char, tag: String, msg: String)

package dev.termish.util

import kotlin.time.TimeSource

/**
 * 轻量 span 追踪：借鉴 OpenTelemetry 的 Span 概念（name / attributes /
 * start-end / status / events），去掉分布式负担（导出、采样、传播）。
 *
 * 用途：连接/重连等慢操作定位阶段耗时——DNS/TCP/KEX/认证卡在哪一步。
 * 输出走 [TermLog]：span 结束时打一行结构化摘要（total + 各 step 耗时 +
 * 状态），>10s 的慢操作自动升级 w 级。
 *
 * 用法（作用域 DSL，自动 begin/end）：
 * ```
 * TermTrace.span("ssh.connect", tag = "ssh", "host" to name) {
 *     onTraceStep("tcp")   // 引擎回调
 *     onTraceStep("kex")
 *     throw/标记失败时自动记录 FAILED
 * }
 * ```
 * 嵌套：span 内可再开子 span（如认证方法级）。
 */
object TermTrace {

    const val SLOW_THRESHOLD_MS = 10_000L

    /** Span 状态。 */
    enum class Status { OK, FAILED }

    /** 单阶段记录：名称 + 该时刻相对 span 开始的耗时。 */
    class Step(val name: String, val elapsedMs: Long)

    class Span internal constructor(
        val name: String,
        private val tag: String,
        private val attrs: Map<String, String>,
        private val startMark: TimeSource.Monotonic.ValueTimeMark,
    ) {
        private val steps = mutableListOf<Step>()
        var status: Status = Status.OK
            private set
        var error: String? = null
            private set
        private var ended = false

        /** 记录阶段完成点（耗时 = 相对 span 开始）。 */
        fun step(step: String) {
            if (ended) return
            steps.add(Step(step, startMark.elapsedNow().inWholeMilliseconds))
        }

        /** 标记失败并结束（输出 E 级）。 */
        fun fail(msg: String?) {
            status = Status.FAILED
            error = msg
            end()
        }

        /** 结束并输出摘要（不显式调用时由作用域 DSL 自动调用）。 */
        fun end() {
            if (ended) return
            ended = true
            val total = startMark.elapsedNow().inWholeMilliseconds
            val stepsText = steps.joinToString(", ") { "${it.name} ${it.elapsedMs}ms" }
            val attrsText = attrs.entries.joinToString(" ") { "${it.key}=${it.value}" }
            val msg = "span[$name] total=${total}ms ${status.name.lowercase()}" +
                (error?.let { "($it)" } ?: "") +
                (if (steps.isEmpty()) "" else " steps=[$stepsText]") +
                (if (attrs.isEmpty()) "" else " $attrsText")
            if (status == Status.FAILED || total >= SLOW_THRESHOLD_MS) {
                TermLog.w(tag) { msg }
            } else {
                TermLog.i(tag) { msg }
            }
        }
    }

    /** 开启 span（手动模式，配合 [Span.step]/[Span.end]；跨函数场景用）。 */
    fun begin(name: String, tag: String = "trace", vararg attrs: Pair<String, String>): Span =
        Span(name, tag, attrs.toMap(), TimeSource.Monotonic.markNow())

    /** 作用域模式：块结束自动 end；抛异常自动 fail 后重抛。 */
    inline fun span(
        name: String,
        tag: String = "trace",
        crossinline body: Span.() -> Unit,
        vararg attrs: Pair<String, String>,
    ) {
        val span = begin(name, tag, *attrs)
        try {
            body(span)
            span.end()
        } catch (e: Throwable) {
            span.fail(e.message)
            throw e
        }
    }
}

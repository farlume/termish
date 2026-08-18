package dev.termish.herdr

import dev.termish.util.TermLog

/**
 * herdr 探测（连接级控制面操作）：在已认证连接上按候选路径逐个试
 * `herdr api snapshot`，第一个返回合法快照的即命中。
 *
 * 探测结果是连接级知识——命中路径必须贯穿下游（mosh 引导 `-- <bin>`、
 * exec 降级 startExec），否则非默认 PATH（~/.local/bin、/opt/homebrew）
 * 场景探测能过但启动必败。
 */
object HerdrProbe {

    /** 探测结果：命中的二进制路径 + 会话快照。 */
    data class Result(
        val bin: String,
        val snapshot: HerdrSessionSnapshot,
    )

    /**
     * 探测 herdr 是否可用。
     *
     * @param runCommand 在已认证连接上执行一次性命令（SshSession.runCommand 的适配，
     *   传入即固定超时；返回 null 视为该候选失败）
     * @return 命中结果；全部候选失败（未安装/未启动/输出非法）返回 null
     */
    fun probe(runCommand: (String) -> String?): Result? {
        for (bin in HerdrApi.BIN_CANDIDATES) {
            val raw = runCommand("$bin api snapshot") ?: continue
            val snapshot = parseHerdrSnapshot(raw) ?: continue
            TermLog.d("herdr") { "probe hit bin=$bin" }
            return Result(bin, snapshot)
        }
        return null
    }
}

package dev.termish.herdr

import dev.termish.util.TermLog

/**
 * herdr 探测（连接级控制面操作）：在已认证连接上按候选路径逐个试
 * `<bin> --version`，第一个返回合法版本输出的即命中。
 *
 * 探测用 `--version` 而非 `api snapshot`：snapshot 需要 herdr daemon 已在
 * 运行（server_not_running 时 exit 1 + error JSON），而探测发生在注入 herdr
 * 之前——刚装完/从未启动过的主机 daemon 必然不在，探测会把「已安装」误判
 * 成「未安装」（引导安装卡片打转、装完仍报失败）。`--version` 不依赖
 * daemon，语义就是「二进制存在且可执行」。
 * （运行期的「daemon 是否在跑」由 HerdrMonitor 用 snapshot 轮询 + 失败退避
 * 表达，两条路径各司其职。）
 *
 * 探测结果是连接级知识——命中路径必须贯穿下游（mosh 引导 `-- <bin>`、
 * exec 降级 startExec），否则非默认 PATH（~/.local/bin、/opt/homebrew）
 * 场景探测能过但启动必败。
 */
object HerdrProbe {
    /** 探测结果：命中的二进制路径。 */
    data class Result(
        val bin: String,
    )

    /**
     * 探测 herdr 是否已安装。
     *
     * @param runCommand 在已认证连接上执行一次性命令（SshSession.runCommand 的适配，
     *   传入即固定超时；返回 null 视为该候选失败）
     * @return 命中结果；全部候选失败（未安装/输出非法）返回 null
     */
    fun probe(runCommand: (String) -> String?): Result? {
        for (bin in HerdrApi.BIN_CANDIDATES) {
            val raw = runCommand("$bin --version") ?: continue
            if (!isVersionOutput(raw)) continue
            TermLog.d("herdr") { "probe hit bin=$bin" }
            return Result(resolveHome(bin, runCommand))
        }
        return null
    }

    /**
     * 版本输出校验：`herdr 0.8.0` 形态（首行以 herdr 开头且带版本 token）。
     * 防 PATH 里同名异物/只打印 usage 的 shim 误判命中。
     */
    internal fun isVersionOutput(raw: String): Boolean =
        raw
            .lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.matches(Regex("herdr\\s+\\S+.*")) == true

    /**
     * `$HOME` 前缀候选 → 绝对路径。
     *
     * 探测命令经远端 shell 执行（runCommand → sshd → 用户 shell），$HOME 会被
     * 展开，所以字面候选能命中；但下游拿到字面路径必失败——mosh 引导
     * `-- '<bin>'` 的单引号阻止 shell 展开，而 mosh-server 子进程对 `--` 后的
     * 参数是直接 execvp（不过 shell，见 mosh-server.cc run_command），字面
     * `$HOME/...` 会报 `execvp: $HOME/...: No such file or directory`；
     * 降级 exec 通道（startExec）的单引号同理不展开。命中结果必须先解析成
     * 绝对路径再贯穿下游。
     *
     * echo 失败的异常环境退回原样（尽力而为：探测至少证明该路径可用）。
     */
    private fun resolveHome(
        bin: String,
        runCommand: (String) -> String?,
    ): String {
        if (!bin.startsWith("\$HOME")) return bin
        val home = runCommand("echo \$HOME")?.trim()?.takeIf { it.startsWith("/") }
        return home?.let { bin.replaceFirst("\$HOME", it) } ?: bin
    }
}

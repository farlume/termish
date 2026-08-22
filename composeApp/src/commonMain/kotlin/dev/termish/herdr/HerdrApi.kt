package dev.termish.herdr

import dev.termish.ssh.CommandOutput

/**
 * herdr CLI 命令构造与输出解析（V1 控制面）。
 *
 * 全部经 SshSession.runCommand 在已认证连接上执行（复用连接、不打断 shell）。
 * 真实行为依据（herdr 0.8.0，实测）：
 * - `herdr api snapshot`：JSON，含 result.snapshot（见 HerdrModels）
 * - `herdr pane read`：终端内容纯文本，无 JSON 包装（AgentDialog 上下文直接显示）
 * - `herdr agent prompt`：成功输出 CLI 包装 JSON；失败时**退出码非零（exit 1）
 *   且错误 JSON 在 stderr**（如 `{"error":{"code":"agent_not_found",...},"id":"cli:agent:prompt"}`）——
 *   必须经 [parseCommandError] 拿 [CommandOutput]（stderr + exitCode）判定，
 *   只读 stdout 会把失败静默当成成功。
 */
object HerdrApi {
    const val SNAPSHOT_CMD = "herdr api snapshot"

    /**
     * herdr 二进制路径候选（sshd 非交互 exec 的 PATH 常不含 ~/.local/bin、
     * /usr/local/bin、/opt/homebrew 等常见安装位置——失败时逐个尝试）。
     * $HOME 由远端 /bin/sh -c 展开（ssh exec 通道的标准执行方式）。
     *
     * 探测命中后 [HerdrProbe] 会把 $HOME 前缀候选解析成绝对路径再返回，
     * 下游必须复用该绝对路径（mosh 引导 `-- <bin>` 时 mosh-server 直接
     * execvp 不过 shell、startExec 单引号均不展开 $HOME）——裸 `herdr` 或
     * 字面 `$HOME/...` 在非默认 PATH 场景探测能过但启动必败。
     */
    val BIN_CANDIDATES =
        listOf(
            "herdr",
            "\$HOME/.local/bin/herdr",
            "/usr/local/bin/herdr",
            "/opt/homebrew/bin/herdr",
        )

    /** snapshot 命令候选（由 [BIN_CANDIDATES] 派生）。 */
    val SNAPSHOT_CMD_CANDIDATES = BIN_CANDIDATES.map { "$it api snapshot" }

    /** pane 最近输出读取（approve 对话框上下文）。输出为纯文本。 */
    fun paneReadCmd(
        paneId: String,
        lines: Int = 30,
    ): String = "herdr pane read $paneId --source recent --lines $lines"

    /** 向 agent 提交回复（手机 approve）。文本经 shell 单引号转义（exec 经 /bin/sh）。 */
    fun agentPromptCmd(
        paneId: String,
        text: String,
    ): String = "herdr agent prompt $paneId ${shQuote(text)}"

    /** shell 单引号转义：`'` → `'\\''`（POSIX 标准技巧，防文本注入）。 */
    fun shQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /**
     * 从命令结果解析 herdr CLI 错误（`{"error":{...}}`）。
     *
     * 判定顺序：
     * 1. stderr/stdout 含 error JSON（herdr 失败时错误 JSON 走 stderr，如 agent_not_found）
     * 2. 退出码非零但无 JSON → 合成错误（code = "exit_<n>"，保留退出码信息）
     * 3. 连接级失败（输出为 null）→ 合成错误（code = "command_failed"），
     *    避免调用方把「命令没跑成」误判为「已提交」
     * 成功（退出码 0 且无 error JSON）返回 null。
     */
    fun parseCommandError(out: CommandOutput?): HerdrApiError? {
        if (out == null) return HerdrApiError("command_failed", "ssh command failed or timed out")
        val raw =
            listOf(out.stderr, out.stdout)
                .firstOrNull { it.contains("{\"error\"") }
                ?: ""
        if (raw.isNotEmpty()) {
            // 只取 error 对象片段（CLI 包装尾部还有 id 字段，容错截断解析）
            val candidate = raw.substring(raw.indexOf("{\"error\""))
            return runCatching {
                kotlinx.serialization.json
                    .Json {
                        ignoreUnknownKeys = true
                    }.decodeFromString<HerdrCliResponse>(candidate)
                    .error
            }.getOrNull() ?: HerdrApiError("unknown", "unparseable herdr error")
        }
        val exit = out.exitCode
        if (exit != null && exit != 0) return HerdrApiError("exit_$exit", "command exited with code $exit")
        return null
    }
}

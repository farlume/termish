package dev.termish.herdr

/**
 * herdr CLI 命令构造与输出解析（V1 控制面）。
 *
 * 全部经 SshSession.runCommand 在已认证连接上执行（复用连接、不打断 shell）。
 * 真实行为依据（herdr 0.8.0）：
 * - `herdr api snapshot`：JSON，含 result.snapshot（见 HerdrModels）
 * - `herdr pane read`：终端内容纯文本，无 JSON 包装（AgentDialog 上下文直接显示）
 * - `herdr agent prompt`：成功输出 CLI 包装 JSON；失败输出
 *   `{"error":{"code":"agent_not_found",...},"id":"cli:agent:prompt"}`（不退出非零）
 */
object HerdrApi {
    const val SNAPSHOT_CMD = "herdr api snapshot"

    /**
     * herdr 二进制路径候选（sshd 非交互 exec 的 PATH 常不含 ~/.local/bin、
     * /usr/local/bin、/opt/homebrew 等常见安装位置——失败时逐个尝试）。
     * $HOME 由远端 /bin/sh -c 展开（ssh exec 通道的标准执行方式）。
     *
     * 探测命中后必须复用全路径（mosh 引导 `-- <bin>`、exec 降级 startExec）——
     * 裸 `herdr` 在非默认 PATH 场景探测能过但启动必败。
     */
    val BIN_CANDIDATES = listOf(
        "herdr",
        "\$HOME/.local/bin/herdr",
        "/usr/local/bin/herdr",
        "/opt/homebrew/bin/herdr",
    )

    /** snapshot 命令候选（由 [BIN_CANDIDATES] 派生）。 */
    val SNAPSHOT_CMD_CANDIDATES = BIN_CANDIDATES.map { "$it api snapshot" }

    /** pane 最近输出读取（approve 对话框上下文）。输出为纯文本。 */
    fun paneReadCmd(paneId: String, lines: Int = 30): String =
        "herdr pane read $paneId --source recent --lines $lines"

    /** 向 agent 提交回复（手机 approve）。文本经 shell 单引号转义（exec 经 /bin/sh）。 */
    fun agentPromptCmd(paneId: String, text: String): String =
        "herdr agent prompt $paneId ${shQuote(text)}"

    /** shell 单引号转义：`'` → `'\\''`（POSIX 标准技巧，防文本注入）。 */
    fun shQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /**
     * 从 CLI 输出解析错误（`{"error":{...}}`）。成功输出不含 error 字段时返回 null。
     * 解析失败（输出不是 JSON）也返回 null——调用方按"命令已执行"处理。
     */
    fun parseCliError(raw: String?): HerdrApiError? {
        if (raw == null) return null
        val idx = raw.indexOf("{\"error\"")
        if (idx < 0) return null
        // 只取 error 对象片段（CLI 包装尾部还有 id 字段，容错截断解析）
        val candidate = raw.substring(idx)
        return runCatching {
            kotlinx.serialization.json.Json {
                ignoreUnknownKeys = true
            }.decodeFromString<HerdrCliResponse>(candidate).error
        }.getOrNull()
    }
}

package dev.termish.herdr

import dev.termish.ssh.CommandOutput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * HerdrApi 命令构造与错误解析测试。
 *
 * 错误形态基于 herdr 0.8.0 实测：失败时退出码非零（exit 1）且错误 JSON 在
 * stderr（如 `herdr agent prompt wW:pxxx hi` → exit 1 +
 * `{"error":{"code":"agent_not_found",...},"id":"cli:agent:prompt"}`）。
 * 只读 stdout 会把失败误判成成功——[HerdrApi.parseCommandError] 必须看
 * stderr + exitCode。
 */
class HerdrApiTest {

    // ---- parseCommandError 黄金用例（真实 CLI 输出样本）----

    @Test
    fun successOutputIsNull() {
        val out = CommandOutput(
            stdout = """{"id":"cli:agent:prompt","result":{"agent":{"agent":"pi","agent_status":"done","pane_id":"wW:p2"}}}""",
            stderr = "",
            exitCode = 0,
        )
        assertNull(HerdrApi.parseCommandError(out))
    }

    @Test
    fun agentNotFoundErrorOnStderr() {
        // 实测：herdr agent prompt 无效 pane → exit 1 + stderr error JSON
        val out = CommandOutput(
            stdout = "",
            stderr = """{"error":{"code":"agent_not_found","message":"agent target wW:pxxx not found"},"id":"cli:agent:prompt"}""",
            exitCode = 1,
        )
        val err = HerdrApi.parseCommandError(out)
        assertEquals("agent_not_found", err?.code)
        assertEquals("agent target wW:pxxx not found", err?.message)
    }

    @Test
    fun paneNotFoundErrorOnStderr() {
        // 实测：herdr pane read 无效 pane → exit 1 + stderr error JSON
        val out = CommandOutput(
            stdout = "",
            stderr = """{"error":{"code":"pane_not_found","message":"pane wW:pxxx not found"},"id":"cli:pane:read"}""",
            exitCode = 1,
        )
        val err = HerdrApi.parseCommandError(out)
        assertEquals("pane_not_found", err?.code)
        assertEquals("pane wW:pxxx not found", err?.message)
    }

    @Test
    fun errorJsonOnStdoutIsAlsoRecognized() {
        // 兼容：错误 JSON 出现在 stdout 时同样识别（部分 CLI 版本/命令）
        val out = CommandOutput(
            stdout = """{"error":{"code":"not_found","message":"pane not found"},"id":"req_1"}""",
            stderr = "",
            exitCode = 1,
        )
        val err = HerdrApi.parseCommandError(out)
        assertEquals("not_found", err?.code)
    }

    @Test
    fun nonZeroExitWithoutJsonYieldsExitCodeError() {
        // herdr 未装 / 命令不存在：stderr 是 shell 的 command not found，无 JSON
        val out = CommandOutput(stdout = "", stderr = "herdr: command not found", exitCode = 127)
        val err = HerdrApi.parseCommandError(out)
        assertEquals("exit_127", err?.code)
    }

    @Test
    fun emptyOutputZeroExitIsSuccess() {
        assertNull(HerdrApi.parseCommandError(CommandOutput("", "", 0)))
    }

    @Test
    fun nullOutputIsFailureNotSuccess() {
        // 连接失败/超时（runCommandDetailed 返回 null）：绝不能当"已提交"处理
        val err = HerdrApi.parseCommandError(null)
        assertEquals("command_failed", err?.code)
    }

    // ---- 命令构造 ----

    @Test
    fun promptCommandQuotesText() {
        assertEquals("herdr agent prompt wW:p2 'hello world'", HerdrApi.agentPromptCmd("wW:p2", "hello world"))
    }

    @Test
    fun promptCommandEscapesSingleQuote() {
        // POSIX 单引号转义：' → '\''，防文本注入（exec 经 /bin/sh）
        assertEquals(
            "herdr agent prompt wW:p2 'it'\\''s ok'",
            HerdrApi.agentPromptCmd("wW:p2", "it's ok"),
        )
    }

    @Test
    fun shQuoteRoundTrip() {
        val samples = listOf("", "plain", "with space", "it's", "a'b'c", "中文", "a\nb")
        for (s in samples) {
            assertEquals(s, shUnquote(HerdrApi.shQuote(s)), "round-trip failed for: $s")
        }
    }

    @Test
    fun paneReadCommandShape() {
        assertEquals(
            "herdr pane read w1:p1 --source recent --lines 30",
            HerdrApi.paneReadCmd("w1:p1"),
        )
    }

    /** 极简 sh 单引号还原（测试用；不处理嵌套）——验证转义无信息丢失。 */
    private fun shUnquote(q: String): String {
        require(q.startsWith("'") && q.endsWith("'"))
        val inner = q.removeSurrounding("'")
        return inner.replace("'\\''", "'")
    }
}

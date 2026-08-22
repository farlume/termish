package dev.termish.herdr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * HerdrProbe 探测：$HOME 前缀候选命中后必须解析成绝对路径返回。
 *
 * 根因（真机踩坑 1）：探测命令经远端 shell 执行会展开 $HOME，字面候选能命中；
 * 但下游 mosh 引导 `-- '<bin>'` 的单引号阻止展开，mosh-server 子进程又是直接
 * execvp（不过 shell），字面 `$HOME/...` 报
 * `execvp: $HOME/...: No such file or directory`。
 *
 * 根因（真机踩坑 2）：探测用 `--version` 而非 `api snapshot`——snapshot 需要
 * daemon 已运行（Linux 实测：server_not_running 时 exit 1 + error JSON），
 * 刚装完/从未启动的主机会被误判成「未安装」（装完仍报失败）。
 */
class HerdrProbeTest {
    private val versionOutput = "herdr 0.8.0"

    @Test
    fun homeCandidateResolvedToAbsolutePath() {
        // exec PATH 缺 ~/.local/bin（裸 herdr 失败）→ $HOME 候选命中 → 解析成绝对路径
        val commands = mutableListOf<String>()
        val result =
            HerdrProbe.probe { cmd ->
                commands += cmd
                when (cmd) {
                    "herdr --version" -> null
                    "\$HOME/.local/bin/herdr --version" -> versionOutput
                    "echo \$HOME" -> "/root"
                    else -> null
                }
            }!!
        assertEquals("/root/.local/bin/herdr", result.bin)
        // 解析发生在命中之后、且只多一次 echo（候选顺序不变）
        assertEquals(
            listOf("herdr --version", "\$HOME/.local/bin/herdr --version", "echo \$HOME"),
            commands,
        )
    }

    @Test
    fun homeCandidateResolvedAfterBareCandidateMissesOtherAbsolutes() {
        // 全候选顺序：herdr → $HOME → /usr/local/bin（都不中）→ /opt/homebrew 命中，
        // 绝对路径候选无需解析（不应发起 echo）
        val commands = mutableListOf<String>()
        val result =
            HerdrProbe.probe { cmd ->
                commands += cmd
                if (cmd == "/opt/homebrew/bin/herdr --version") versionOutput else null
            }!!
        assertEquals("/opt/homebrew/bin/herdr", result.bin)
        assertEquals(HerdrApi.BIN_CANDIDATES.map { "$it --version" }, commands)
    }

    @Test
    fun bareCandidateHitNeedsNoResolution() {
        // PATH 直接可用的常规场景：原样返回，不额外探测
        val commands = mutableListOf<String>()
        val result =
            HerdrProbe.probe { cmd ->
                commands += cmd
                if (cmd == "herdr --version") versionOutput else null
            }!!
        assertEquals("herdr", result.bin)
        assertEquals(listOf("herdr --version"), commands)
    }

    @Test
    fun daemonNotRunningIsStillInstalled() {
        // 回归（Linux 实测）：api snapshot 在 daemon 未运行时返回 error JSON + exit 1。
        // 探测已改用 --version——「装了但没启动」必须命中，不能误判成未安装
        val snapshotServerError = """{"id":"x","error":{"code":"server_not_running","message":"no herdr server is running"}}"""
        val result =
            HerdrProbe.probe { cmd ->
                when (cmd) {
                    "herdr --version" -> versionOutput
                    else -> snapshotServerError
                }
            }!!
        assertEquals("herdr", result.bin)
    }

    @Test
    fun homeEchoFailureFallsBackToRawCandidate() {
        // echo $HOME 失败（异常环境）：退回原样尽力而为，不阻断连接
        val result =
            HerdrProbe.probe { cmd ->
                when (cmd) {
                    "\$HOME/.local/bin/herdr --version" -> versionOutput
                    else -> null
                }
            }!!
        assertEquals("\$HOME/.local/bin/herdr", result.bin)
    }

    @Test
    fun allCandidatesFailReturnsNull() {
        assertNull(HerdrProbe.probe { null })
    }

    @Test
    fun versionOutputValidation() {
        assertTrue(HerdrProbe.isVersionOutput("herdr 0.8.0"))
        assertTrue(HerdrProbe.isVersionOutput("\nherdr 0.8.0\n"))
        assertTrue(HerdrProbe.isVersionOutput("herdr 1.2.3-beta+abc"))
        // 同名异物/usage 输出不误判命中
        assertFalse(HerdrProbe.isVersionOutput("usage: herdr [options]"))
        assertFalse(HerdrProbe.isVersionOutput("herdr"))
        assertFalse(HerdrProbe.isVersionOutput(""))
        // snapshot 的 error JSON 不是版本输出
        assertFalse(HerdrProbe.isVersionOutput("""{"id":"x","error":{"code":"server_not_running"}}"""))
    }
}

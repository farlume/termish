package dev.termish.herdr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * HerdrProbe 探测：$HOME 前缀候选命中后必须解析成绝对路径返回。
 *
 * 根因（真机踩坑）：探测命令经远端 shell 执行会展开 $HOME，字面候选能命中；
 * 但下游 mosh 引导 `-- '<bin>'` 的单引号阻止展开，mosh-server 子进程又是直接
 * execvp（不过 shell），字面 `$HOME/...` 报
 * `execvp: $HOME/.local/bin/herdr: No such file or directory`。
 */
class HerdrProbeTest {

    private val snapshotJson = """{"id":"x","result":{"snapshot":{}}}"""

    @Test
    fun homeCandidateResolvedToAbsolutePath() {
        // exec PATH 缺 ~/.local/bin（裸 herdr 失败）→ $HOME 候选命中 → 解析成绝对路径
        val commands = mutableListOf<String>()
        val result = HerdrProbe.probe { cmd ->
            commands += cmd
            when (cmd) {
                "herdr api snapshot" -> null
                "\$HOME/.local/bin/herdr api snapshot" -> snapshotJson
                "echo \$HOME" -> "/root"
                else -> null
            }
        }!!
        assertEquals("/root/.local/bin/herdr", result.bin)
        // 解析发生在命中之后、且只多一次 echo（候选顺序不变）
        assertEquals(
            listOf("herdr api snapshot", "\$HOME/.local/bin/herdr api snapshot", "echo \$HOME"),
            commands,
        )
    }

    @Test
    fun homeCandidateResolvedAfterBareCandidateMissesOtherAbsolutes() {
        // 全候选顺序：herdr → $HOME → /usr/local/bin（都不中）→ /opt/homebrew 命中，
        // 绝对路径候选无需解析（不应发起 echo）
        val commands = mutableListOf<String>()
        val result = HerdrProbe.probe { cmd ->
            commands += cmd
            if (cmd == "/opt/homebrew/bin/herdr api snapshot") snapshotJson else null
        }!!
        assertEquals("/opt/homebrew/bin/herdr", result.bin)
        assertEquals(HerdrApi.BIN_CANDIDATES.map { "$it api snapshot" }, commands)
    }

    @Test
    fun bareCandidateHitNeedsNoResolution() {
        // PATH 直接可用的常规场景：原样返回，不额外探测
        val commands = mutableListOf<String>()
        val result = HerdrProbe.probe { cmd ->
            commands += cmd
            if (cmd == "herdr api snapshot") snapshotJson else null
        }!!
        assertEquals("herdr", result.bin)
        assertEquals(listOf("herdr api snapshot"), commands)
    }

    @Test
    fun homeEchoFailureFallsBackToRawCandidate() {
        // echo $HOME 失败（异常环境）：退回原样尽力而为，不阻断连接
        val result = HerdrProbe.probe { cmd ->
            when (cmd) {
                "\$HOME/.local/bin/herdr api snapshot" -> snapshotJson
                else -> null
            }
        }!!
        assertEquals("\$HOME/.local/bin/herdr", result.bin)
    }

    @Test
    fun allCandidatesFailReturnsNull() {
        assertNull(HerdrProbe.probe { null })
    }
}

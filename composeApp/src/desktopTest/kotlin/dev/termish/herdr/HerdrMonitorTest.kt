package dev.termish.herdr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 真实 herdr 快照解析测试：fixture 抓自本机运行的 herdr 0.8.0
 * （`herdr api snapshot` 原样输出，见 resources/herdr-snapshot.json），
 * 保证模型与真实协议结构对齐（防 fixture 自洽偏差）。
 */
class HerdrSnapshotRealDataTest {
    private fun realSnapshot(): HerdrSessionSnapshot {
        val raw =
            HerdrSnapshotRealDataTest::class.java
                .getResourceAsStream("/herdr-snapshot.json")
                ?.readBytes()
                ?.decodeToString()
                ?: error("fixture 缺失: desktopTest resources 里应有 herdr-snapshot.json（herdr api snapshot 原样输出）")
        return assertNotNull(parseHerdrSnapshot(raw), "真实 herdr 快照应解析成功")
    }

    @Test
    fun realSnapshotParses() {
        val s = realSnapshot()
        assertTrue(s.protocol > 0, "protocol 应非零（真实协议版本）")
        assertTrue(s.panes.isNotEmpty())
        assertEquals(
            s.agents.size,
            s.agents
                .map { it.paneId }
                .toSet()
                .size,
            "agent pane_id 应唯一",
        )
    }

    @Test
    fun realSnapshotAgentsHaveExpectedShape() {
        val s = realSnapshot()
        // 抓取时的真实状态：两个 pi agent（idle + working）
        assertTrue(s.agents.isNotEmpty())
        for (a in s.agents) {
            assertNotNull(a.paneId)
            assertTrue(a.agentStatus != HerdrAgentStatus.UNKNOWN || a.agent == null)
        }
        println("real agents: ${s.agents.map { "${it.agent}/${it.agentStatus}/${it.paneId}" }}")
    }

    @Test
    fun stateMachineRunsOnRealData() {
        val s = realSnapshot()
        val m = HerdrAgentStateMachine()
        val events = m.update(s)
        assertEquals(s.agents.size, events.size, "首轮应为首状态事件")
        // 同一快照再喂：无变化
        assertTrue(m.update(s).isEmpty())
        println("real events: ${events.map { it::class.simpleName }}")
    }
}

/**
 * HerdrMonitor 轮询测试：kotlinx-coroutines-test 虚拟时间驱动，
 * fake runCommand 返回可控快照序列，验证轮询调用、状态事件回调、
 * blocked 确认通知文本、停止语义。
 */
class HerdrMonitorTest {
    private fun snapshotWith(vararg statuses: Pair<String, HerdrAgentStatus>): String {
        val agents =
            statuses.joinToString(",") { (pane, st) ->
                """{"pane_id":"$pane","agent":"pi","agent_status":"${st.name.lowercase()}","terminal_title":"t-$pane"}"""
            }
        return """{"id":"cli:api:snapshot","result":{"snapshot":{"version":"0.8.0","protocol":19,"workspaces":[],"tabs":[],"panes":[],"agents":[$agents],"layouts":[]}}}"""
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun pollsAndEmitsEvents() =
        kotlinx.coroutines.test.runTest {
            val commands = mutableListOf<String>()
            val received = mutableListOf<List<HerdrAgentEvent>>()
            var round = 0
            val monitor =
                HerdrMonitor(
                    hostName = "nas",
                    hostId = "h1",
                    runCommand = { cmd ->
                        commands += cmd
                        round++
                        // 第 1 轮：working；第 2 轮起：blocked
                        if (round == 1) {
                            snapshotWith("w1:p1" to HerdrAgentStatus.WORKING)
                        } else {
                            snapshotWith("w1:p1" to HerdrAgentStatus.BLOCKED)
                        }
                    },
                    scope = this,
                    pollIntervalMs = 1_000,
                    onEvents = { received += it },
                )
            monitor.start()
            testScheduler.advanceTimeBy(10_000)
            // 断言在 stop 前：stop() 会重置状态机
            assertEquals(HerdrAgentStatus.BLOCKED, monitor.currentStatus()["w1:p1"])
            monitor.stop()

            assertTrue(commands.isNotEmpty(), "轮询应执行 herdr api snapshot")
            assertTrue(commands.all { it == "herdr api snapshot" })
            // 事件流：首轮 Working，之后一轮 Blocked（状态稳定后不再重复发）
            val all = received.flatten()
            assertIs<HerdrAgentEvent.Working>(all.first())
            assertIs<HerdrAgentEvent.Blocked>(all[1])
            assertEquals(2, all.size, "状态稳定后不应重复发事件")
        }

    @Test
    fun blockedNotificationText() {
        val monitor =
            HerdrMonitor(
                "nas",
                "h1",
                { null },
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            )
        val a =
            HerdrAgentInfo(
                paneId = "w1:p1",
                agent = "codex",
                agentStatus = HerdrAgentStatus.BLOCKED,
                terminalTitleStripped = "codex - refactor auth",
                cwd = "/repo",
            )
        assertEquals("codex 在 nas 等待回答：codex - refactor auth", monitor.blockedNotificationText("nas", a))
        // 无 title 无 cwd 的兜底
        val bare = HerdrAgentInfo(paneId = "w1:p1", agentStatus = HerdrAgentStatus.BLOCKED)
        assertEquals("agent 在 nas 等待回答：请查看会话", monitor.blockedNotificationText("nas", bare))
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun failureBackoffThenPause() =
        kotlinx.coroutines.test.runTest {
            val calls = mutableListOf<String>()
            val monitor =
                HerdrMonitor(
                    hostName = "nas",
                    hostId = "h1",
                    runCommand = { cmd ->
                        calls += cmd
                        null
                    }, // 永远失败（herdr 未装）
                    scope = this,
                    pollIntervalMs = 1_000,
                )
            monitor.start()
            testScheduler.advanceTimeBy(120_000) // 2 分钟：3 次失败 → 暂停 → 恢复后重试
            monitor.stop()
            // 前 3 次退避 3+6+12=21s 内完成，之后每 60s 暂停 + 3 次重试（3+6+12）
            // 120s 内约 6+6=12 次调用量级，但绝不能是每 1s 一次的 120 次（防轰炸）
            assertTrue(calls.size < 30, "连续失败必须退避+暂停，实际调用 ${calls.size} 次")
            assertTrue(calls.size >= 6, "暂停后应自动恢复轮询，实际调用 ${calls.size} 次")
        }
}

package dev.termish.herdr

/**
 * agent 状态机：快照 diff → 状态迁移事件。
 *
 * 纯函数式、无 IO 无 UI——输入每轮快照的 agents 列表，输出与上一轮相比的
 * 状态迁移事件。blocked 进入/离开是通知与 UI 徽章的源头。
 *
 * 判定基准：agents 数组（herdr 已做 agent 检测），按 pane_id 跟踪。
 */
sealed interface HerdrAgentEvent {
    /** agent 进入 blocked（等待用户回答/批准）。 */
    data class Blocked(
        val paneId: String,
        val agent: String?,
        val title: String?,
        val cwd: String?,
    ) : HerdrAgentEvent

    /** agent 离开 blocked（用户回复/任务恢复）。 */
    data class Unblocked(val paneId: String, val agent: String?) : HerdrAgentEvent

    /** agent 开始 working。 */
    data class Working(val paneId: String, val agent: String?) : HerdrAgentEvent

    /** agent 变为 idle（含 done 语义的降级显示）。 */
    data class Idle(val paneId: String, val agent: String?) : HerdrAgentEvent
}

class HerdrAgentStateMachine {

    /** pane_id → 上次快照中的状态（跨轮跟踪）。 */
    private val prev = HashMap<String, HerdrAgentStatus>()

    /** 当前各 pane 状态快照（供 UI 读取/测试断言）。 */
    fun current(): Map<String, HerdrAgentStatus> = prev.toMap()

    /** 全量重置（监控重启/重连后调用，避免把重启前的状态当迁移）。 */
    fun reset() {
        prev.clear()
    }

    /**
     * 喂入一轮快照，返回相对上一轮的状态迁移事件。
     * 同一状态重复出现不产生事件（轮询去抖的天然基础）。
     */
    fun update(snapshot: HerdrSessionSnapshot): List<HerdrAgentEvent> {
        val events = ArrayList<HerdrAgentEvent>()
        val cur = snapshot.agents.associate { it.paneId to it.agentStatus }

        for (a in snapshot.agents) {
            val before = prev[a.paneId]
            if (before == a.agentStatus) continue
            events += when (a.agentStatus) {
                HerdrAgentStatus.BLOCKED -> HerdrAgentEvent.Blocked(
                    a.paneId, a.agent, a.terminalTitleStripped ?: a.terminalTitle, a.cwd,
                )
                HerdrAgentStatus.WORKING -> HerdrAgentEvent.Working(a.paneId, a.agent)
                HerdrAgentStatus.IDLE -> HerdrAgentEvent.Idle(a.paneId, a.agent)
                else -> null
            } ?: continue
            // blocked → 非 blocked：离开等待态（approve 完成/超时/关闭）
            if (before == HerdrAgentStatus.BLOCKED && a.agentStatus != HerdrAgentStatus.BLOCKED) {
                events += HerdrAgentEvent.Unblocked(a.paneId, a.agent)
            }
        }

        // 从快照消失的 pane（会话/工作区关闭）：若之前 blocked 需补 Unblocked，
        // 否则通知会悬挂（手机一直以为 agent 在等你）
        prev.keys.filter { it !in cur }.forEach { gone ->
            if (prev[gone] == HerdrAgentStatus.BLOCKED) {
                events += HerdrAgentEvent.Unblocked(gone, null)
            }
            prev.remove(gone)
        }
        prev.putAll(cur)
        return events
    }
}

package dev.termish.herdr

import dev.termish.notify.NotificationCenter
import dev.termish.notify.NotificationEvent
import dev.termish.util.TermLog
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * herdr agent 监控器（V1：轮询 `herdr api snapshot`）。
 *
 * 挂在主机级（每主机一个）：通过已认证 SSH 连接的 [runCommand] 执行
 * `herdr api snapshot`，快照经 [HerdrAgentStateMachine] 产生状态迁移事件，
 * 回调给 UI（徽章/面板）；blocked 经连续两轮确认后发 AGENT_TASK 通知
 * （手机 approve 的入口）。
 *
 * 纪律：
 * - 轮询间隔带 ±20% 抖动防惊群；调用方负责只在前台且主机有活跃会话时
 *   start()（电池），退后台/会话关闭时 stop()
 * - 失败退避 3s→15s；连续 3 次失败暂停 60s（herdr 未安装/服务器未启动时
 *   不空转轰炸）
 * - blocked 通知防抖：进入 blocked 后需连续两轮确认才发，瞬时 blocked
 *   （agent 自问自答）不误报；Unblocked/会话消失即取消
 */
class HerdrMonitor(
    private val hostName: String,
    private val hostId: String,
    /** 在已认证连接上执行控制面命令（SshSession.runCommand）。 */
    private val runCommand: (String) -> String?,
    private val scope: CoroutineScope,
    private val pollIntervalMs: Long = 4_000,
    /** 状态迁移事件回调（UI 徽章/面板刷新）。 */
    private val onEvents: (List<HerdrAgentEvent>) -> Unit = {},
    /** 每轮快照后的完整 agents 列表回调（UI 面板数据源；含无变化轮）。 */
    private val onAgents: (List<HerdrAgentInfo>) -> Unit = {},
) {
    companion object {
        /**
         * snapshot 命令候选（轮换探测，命中即固定到下标）。
         * 单一事实源在 [HerdrApi.SNAPSHOT_CMD_CANDIDATES]。
         */
        private val SNAPSHOT_CMD_CANDIDATES = HerdrApi.SNAPSHOT_CMD_CANDIDATES
        private const val MIN_BACKOFF_MS = 3_000L
        private const val MAX_BACKOFF_MS = 15_000L

        /** 连续失败后的暂停轮数（≈60s @ 4s 轮询）：纯 delay 驱动，测试可虚拟时间推进。 */
        private const val PAUSE_ROUNDS = 15
        private const val MAX_CONSECUTIVE_FAILURES = 3

        /** blocked 确认轮数：进入后需连续两轮仍 blocked 才发通知。 */
        private const val BLOCKED_CONFIRM_ROUNDS = 2
    }

    private val machine = HerdrAgentStateMachine()
    private var job: Job? = null

    /** pane_id → 已确认 blocked 的轮数（达到阈值即发通知并移除）。 */
    private val pendingNotify = HashMap<String, Int>()

    /** 已发过 blocked 通知的 pane（防重复通知；Unblocked/离开 blocked 时清除）。 */
    private val notifiedBlocked = HashSet<String>()

    /** 当前使用的 herdr 命令候选下标（失败轮询时切换探测）。 */
    private var cmdIndex = 0

    /** 是否正在轮询。 */
    fun isRunning(): Boolean = job?.isActive == true

    /** 当前各 pane 状态（UI 读取）。 */
    fun currentStatus(): Map<String, HerdrAgentStatus> = machine.current()

    fun start() {
        if (job?.isActive == true) return
        machine.reset()
        pendingNotify.clear()
        notifiedBlocked.clear()
        TermLog.i("herdr") { "monitor start $hostName" }
        job =
            scope.launch {
                var backoffMs = MIN_BACKOFF_MS
                var failures = 0
                var pauseRounds = 0
                while (isActive) {
                    // 连续失败暂停期：不轰炸远端（herdr 未装/未启动/连接断开）
                    if (pauseRounds > 0) {
                        pauseRounds--
                        delay(jitter(pollIntervalMs))
                        continue
                    }
                    val raw = runCommand(currentCmd())
                    val snapshot = raw?.let { parseHerdrSnapshot(it) }
                    if (snapshot == null) {
                        // 失败退避：herdr 未装/未启动/连接断开；换下一个候选命令（循环探测）
                        cmdIndex = (cmdIndex + 1) % SNAPSHOT_CMD_CANDIDATES.size
                        failures++
                        backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                        TermLog.d("herdr") { "poll failed $hostName #$failures: ${raw?.take(80) ?: "null"}" }
                        if (failures >= MAX_CONSECUTIVE_FAILURES) {
                            pauseRounds = PAUSE_ROUNDS
                            failures = 0
                            backoffMs = MIN_BACKOFF_MS
                        }
                        delay(backoffMs)
                        continue
                    }
                    failures = 0
                    backoffMs = MIN_BACKOFF_MS
                    val events = machine.update(snapshot)
                    if (events.isNotEmpty()) {
                        TermLog.d(
                            "herdr",
                        ) { "events $hostName: ${events.joinToString { it::class.simpleName ?: "?" }}" }
                        onEvents(events)
                    }
                    onAgents(snapshot.agents)
                    confirmBlocked(snapshot)
                    delay(jitter(pollIntervalMs))
                }
            }
    }

    fun stop() {
        job?.cancel()
        job = null
        machine.reset()
        pendingNotify.clear()
        notifiedBlocked.clear()
        TermLog.i("herdr") { "monitor stop $hostName" }
    }

    /**
     * blocked 确认与通知：新 Blocked 事件进入确认队列；已确认 ≥2 轮的
     * pane 若仍 blocked 发 AGENT_TASK 通知（Unblocked/状态离开由
     * [onEvents] 路径同步清除）。
     */
    private fun confirmBlocked(snapshot: HerdrSessionSnapshot) {
        val current = machine.current()
        // 已离开 blocked 的 pane 移出确认队列
        pendingNotify.keys.filter { current[it] != HerdrAgentStatus.BLOCKED }.forEach { pendingNotify.remove(it) }
        for (a in snapshot.agents) {
            if (a.agentStatus != HerdrAgentStatus.BLOCKED) continue
            val rounds = (pendingNotify[a.paneId] ?: 0) + 1
            if (rounds >= BLOCKED_CONFIRM_ROUNDS) {
                pendingNotify.remove(a.paneId)
                // 防重复：同一 pane 连续 blocked 只通知一次（Unblocked 才重置）
                if (notifiedBlocked.add(a.paneId)) {
                    postBlockedNotification(a)
                }
            } else {
                pendingNotify[a.paneId] = rounds
            }
        }
    }

    private fun postBlockedNotification(a: HerdrAgentInfo) {
        TermLog.w("herdr") { "agent BLOCKED $hostName ${a.paneId} agent=${a.agent} title=${a.title()}" }
        NotificationCenter.post(
            NotificationEvent.AGENT_TASK,
            "Termish",
            blockedNotificationText(hostName, a),
            hostId = hostId,
        )
    }

    /** 通知正文（独立纯函数便于测试）。 */
    internal fun blockedNotificationText(
        hostName: String,
        a: HerdrAgentInfo,
    ): String {
        val agent = a.agent?.takeIf { it.isNotBlank() } ?: "agent"
        return "$agent 在 $hostName 等待回答：${a.title() ?: a.cwd ?: "请查看会话"}"
    }

    private fun currentCmd(): String = SNAPSHOT_CMD_CANDIDATES[cmdIndex]

    private fun HerdrAgentInfo.title(): String? = terminalTitleStripped ?: terminalTitle

    private fun jitter(base: Long): Long = (base * (0.8 + Random.nextDouble() * 0.4)).toLong()
}

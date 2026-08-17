package dev.termish.herdr

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * herdr（the runtime your coding agents live on）agent 状态模型。
 *
 * 本文件只描述 V1 轮询所需的最小字段集（`herdr api snapshot` 输出子集），
 * 解析按 ignoreUnknownKeys 容错——herdr 升级加字段不破坏客户端。
 * 完整协议见 herdr 仓库 docs/socket-api.mdx（session.snapshot / events.subscribe）。
 */

/** herdr agent 语义状态（pane.agent_status）。 */
@Serializable
enum class HerdrAgentStatus {
    @SerialName("idle") IDLE,
    @SerialName("working") WORKING,
    @SerialName("blocked") BLOCKED,
    @SerialName("done") DONE,
    @SerialName("unknown") UNKNOWN,
}

/** 会话快照中的单个 pane（终端格子）。 */
@Serializable
data class HerdrPaneInfo(
    @SerialName("pane_id") val paneId: String,
    @SerialName("workspace_id") val workspaceId: String = "",
    @SerialName("tab_id") val tabId: String = "",
    /** 检测到的 agent 名（pi / codex / claude …）；非 agent pane 为 null。 */
    val agent: String? = null,
    @SerialName("display_agent") val displayAgent: String? = null,
    @SerialName("agent_status") val agentStatus: HerdrAgentStatus = HerdrAgentStatus.UNKNOWN,
    @SerialName("terminal_title") val terminalTitle: String? = null,
    @SerialName("terminal_title_stripped") val terminalTitleStripped: String? = null,
    val cwd: String? = null,
    val focused: Boolean = false,
    val revision: Long = 0,
)

/** 会话快照中检测到的 agent（agents 数组项；与 panes 数组并行）。 */
@Serializable
data class HerdrAgentInfo(
    @SerialName("pane_id") val paneId: String,
    @SerialName("workspace_id") val workspaceId: String = "",
    @SerialName("tab_id") val tabId: String = "",
    val agent: String? = null,
    @SerialName("display_agent") val displayAgent: String? = null,
    @SerialName("agent_status") val agentStatus: HerdrAgentStatus = HerdrAgentStatus.UNKNOWN,
    @SerialName("terminal_title") val terminalTitle: String? = null,
    @SerialName("terminal_title_stripped") val terminalTitleStripped: String? = null,
    val cwd: String? = null,
    val focused: Boolean = false,
    @SerialName("state_change_seq") val stateChangeSeq: Long = 0,
    val revision: Long = 0,
)

/** `herdr api snapshot` 的顶层会话快照。 */
@Serializable
data class HerdrSessionSnapshot(
    val version: String = "",
    val protocol: Long = 0,
    val workspaces: List<HerdrWorkspaceInfo> = emptyList(),
    val tabs: List<HerdrTabInfo> = emptyList(),
    val panes: List<HerdrPaneInfo> = emptyList(),
    val agents: List<HerdrAgentInfo> = emptyList(),
)

@Serializable
data class HerdrWorkspaceInfo(
    @SerialName("workspace_id") val workspaceId: String,
    val label: String = "",
    @SerialName("pane_count") val paneCount: Int = 0,
    @SerialName("tab_count") val tabCount: Int = 0,
)

@Serializable
data class HerdrTabInfo(
    @SerialName("tab_id") val tabId: String,
    @SerialName("workspace_id") val workspaceId: String = "",
    val label: String = "",
    @SerialName("pane_count") val paneCount: Int = 0,
)

/** herdr api 错误（CLI 输出兼容）。 */
@Serializable
data class HerdrApiError(
    val code: String = "",
    val message: String = "",
)

/** `herdr api snapshot` CLI 输出：`{"id","result":{"snapshot":…}}`（raw socket 响应 result 结构同源）。 */
@Serializable
data class HerdrCliResponse(
    val id: String = "",
    val result: HerdrCliResult? = null,
    val error: HerdrApiError? = null,
)

@Serializable
data class HerdrCliResult(
    val snapshot: HerdrSessionSnapshot? = null,
    /** raw socket 响应的 type 字段（如 session_snapshot）；CLI 输出无此字段。 */
    val type: String? = null,
)

private val json = Json {
    ignoreUnknownKeys = true // herdr 升级加字段不破坏客户端
    coerceInputValues = true // 空串/缺失枚举值按默认处理
}

/**
 * 解析 `herdr api snapshot` 输出。CLI 输出与 raw socket 响应的
 * `result` 结构同源（CLI 就是 socket 客户端），统一按
 * `result.snapshot` 提取；解析失败返回 null（不抛）。
 */
fun parseHerdrSnapshot(raw: String): HerdrSessionSnapshot? = try {
    json.decodeFromString<HerdrCliResponse>(raw).result?.snapshot
} catch (_: Exception) {
    null
}

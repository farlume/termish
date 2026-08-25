package dev.termish.agent

enum class AgentBridgeState {
    IDLE,
    CONNECTING,
    NEEDS_INSTALL,
    INSTALLING,
    READY,
    NO_PYTHON,
    ERROR,
}

data class AgentBridgeAgent(
    val id: String,
    val label: String,
    val available: Boolean,
    val supported: Boolean,
)

data class AgentBridgeSessionInfo(
    val id: String,
    val agent: String,
    val title: String,
    val cwd: String,
    val busy: Boolean,
    val messageCount: Int,
    val model: String? = null,
    val provider: String? = null,
    val waitingApproval: Boolean = false,
)

/** Agent CLI 自己保存在远端主机上的可恢复会话。 */
data class AgentNativeSessionInfo(
    val nativeId: String,
    val agent: String,
    val title: String,
    val cwd: String,
    val messageCount: Int,
    val updatedAt: Long,
    val importedSessionId: String? = null,
)

data class AgentApprovalRequest(
    val id: String,
    val sessionId: String,
    val turnId: String,
    val agent: String,
    val kind: String,
    val title: String,
    val message: String,
    val command: String,
    val cwd: String,
    val details: String,
    val placeholder: String,
    val prefill: String,
    val options: List<String>,
    val secret: Boolean = false,
    val allowCustom: Boolean = false,
    val timeoutMs: Long = 0L,
)

/** 仅在当前 SSH 请求中传递的供应商凭据；Bridge 不持久化 [apiKey]。 */
data class AgentProviderRuntime(
    val id: String,
    val type: String,
    val apiKey: String,
    val baseUrl: String,
    /** claude 专用 anthropic 兼容端点（openai 类型供应商提供时可用）。 */
    val anthropicBaseUrl: String = "",
    /** Pi CLI 自己的 provider id（例如 deepseek），与 [type] 的 wire 协议分离。 */
    val piProvider: String = "",
)

data class AgentAttachment(
    val name: String,
    val remotePath: String,
    val size: Long,
)

/** Agent 在远端本轮生成或修改的可交付文件。 */
data class AgentArtifact(
    val name: String,
    val remotePath: String,
    val size: Long,
    val kind: String,
)

data class AgentPendingAttachment(
    val name: String,
    val size: Long,
    val readChunk: () -> ByteArray?,
)

data class AgentChatMessage(
    val role: String,
    val text: String,
    val id: String = "",
    val turnId: String = "",
    val activityId: String? = null,
    val agent: String? = null,
    val toolName: String? = null,
    val toolInput: String? = null,
    val isError: Boolean = false,
    val running: Boolean = false,
    val createdAt: Long = 0L,
    val completedAt: Long? = null,
    val attachments: List<AgentAttachment> = emptyList(),
    val artifacts: List<AgentArtifact> = emptyList(),
)

enum class AgentInstallPhase {
    IDLE,
    INSTALLING,
    SUCCEEDED,
    FAILED,
}

data class AgentInstallStatus(
    val agentId: String? = null,
    val phase: AgentInstallPhase = AgentInstallPhase.IDLE,
    val detail: String = "",
)

data class AgentDirectory(
    val name: String,
    val path: String,
)

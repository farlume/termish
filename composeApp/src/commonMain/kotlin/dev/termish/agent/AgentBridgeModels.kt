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
)

/** 仅在当前 SSH 请求中传递的供应商凭据；Bridge 不持久化 [apiKey]。 */
data class AgentProviderRuntime(
    val id: String,
    val type: String,
    val apiKey: String,
    val baseUrl: String,
)

data class AgentAttachment(
    val name: String,
    val remotePath: String,
    val size: Long,
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

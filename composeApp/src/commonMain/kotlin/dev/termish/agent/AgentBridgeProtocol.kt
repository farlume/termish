package dev.termish.agent

import kotlinx.serialization.json.JsonObject

internal const val AGENT_MESSAGE_PAGE_SIZE = 120
internal const val AGENT_MAX_NDJSON_LINE_BYTES = 8 * 1024 * 1024
internal const val AGENT_STREAM_FLUSH_INTERVAL_MS = 32L
internal const val AGENT_INSTALL_LOG_LIMIT = 8_000

internal data class AgentEventCursor(
    val epoch: String,
    val sequence: Long,
)

internal enum class AgentEventDecision { APPLY, DUPLICATE, GAP, RESET }

internal fun classifyAgentEvent(
    cursor: AgentEventCursor?,
    epoch: String,
    sequence: Long,
): AgentEventDecision =
    when {
        epoch.isBlank() || sequence <= 0L -> AgentEventDecision.APPLY
        cursor == null || cursor.epoch != epoch -> AgentEventDecision.RESET
        sequence <= cursor.sequence -> AgentEventDecision.DUPLICATE
        sequence != cursor.sequence + 1L -> AgentEventDecision.GAP
        else -> AgentEventDecision.APPLY
    }

/** prompt.send 的响应交接闸门；同一 SSH reader 内保持事件原始到达顺序。 */
internal class AgentPromptEventBuffer {
    private val queues = mutableMapOf<String, MutableList<JsonObject>>()

    fun begin(sessionId: String) {
        queues.getOrPut(sessionId, ::mutableListOf)
    }

    fun enqueue(
        sessionId: String?,
        envelope: JsonObject,
    ): Boolean {
        val queue = sessionId?.let(queues::get) ?: return false
        queue += envelope
        return true
    }

    fun drain(sessionId: String): List<JsonObject> = queues.remove(sessionId).orEmpty()

    fun discard(sessionId: String) {
        queues.remove(sessionId)
    }

    fun clear() {
        queues.clear()
    }
}

/** 有上限、复用缓冲区的 NDJSON 解码器，避免每个 SSH chunk 都复制剩余字节。 */
internal class AgentNdjsonDecoder(
    private val maxLineBytes: Int = AGENT_MAX_NDJSON_LINE_BYTES,
) {
    private var buffer = ByteArray(8 * 1024)
    private var size = 0

    fun append(chunk: ByteArray): List<String> {
        ensureCapacity(size + chunk.size)
        chunk.copyInto(buffer, size)
        size += chunk.size

        val lines = mutableListOf<String>()
        var lineStart = 0
        var index = 0
        while (index < size) {
            if (buffer[index] == '\n'.code.toByte()) {
                var lineEnd = index
                if (lineEnd > lineStart && buffer[lineEnd - 1] == '\r'.code.toByte()) lineEnd -= 1
                val lineSize = lineEnd - lineStart
                require(lineSize <= maxLineBytes) { "Agent Bridge response is too large" }
                if (lineSize > 0) lines += buffer.decodeToString(lineStart, lineEnd)
                lineStart = index + 1
            } else if (index - lineStart >= maxLineBytes) {
                throw IllegalArgumentException("Agent Bridge response is too large")
            }
            index += 1
        }
        if (lineStart > 0) {
            buffer.copyInto(buffer, 0, lineStart, size)
            size -= lineStart
        }
        return lines
    }

    fun reset() {
        size = 0
    }

    private fun ensureCapacity(required: Int) {
        if (required <= buffer.size) return
        var capacity = buffer.size
        while (capacity < required) capacity = minOf(maxLineBytes + 1, capacity * 2)
        require(capacity >= required) { "Agent Bridge response is too large" }
        buffer = buffer.copyOf(capacity)
    }
}

internal data class AgentStreamDelta(
    val role: String,
    val activityId: String?,
    val turnId: String,
    val createdAt: Long,
    val text: String,
)

/** 将高频 token 合并成帧级更新，保持不同 activity 首次出现的顺序。 */
internal class AgentStreamBatcher {
    private data class PendingDelta(
        val role: String,
        val activityId: String?,
        val turnId: String,
        val createdAt: Long,
        val text: StringBuilder = StringBuilder(),
    )

    private val pending = linkedMapOf<String, PendingDelta>()

    val isEmpty: Boolean get() = pending.isEmpty()

    fun append(
        role: String,
        activityId: String?,
        turnId: String,
        createdAt: Long,
        text: String,
    ) {
        if (text.isEmpty()) return
        val key = "$role\u0000${activityId.orEmpty()}"
        pending.getOrPut(key) { PendingDelta(role, activityId, turnId, createdAt) }.text.append(text)
    }

    fun drain(): List<AgentStreamDelta> {
        if (pending.isEmpty()) return emptyList()
        val result =
            pending.values.map {
                AgentStreamDelta(it.role, it.activityId, it.turnId, it.createdAt, it.text.toString())
            }
        pending.clear()
        return result
    }

    fun clear() {
        pending.clear()
    }
}

internal fun agentRequestTimeoutMillis(method: String): Long =
    when (method) {
        "agents.install" -> 10 * 60_000L
        "sessions.nativeList", "sessions.nativeImport", "providers.fetchModels" -> 60_000L
        "sessions.get" -> 45_000L
        "prompt.abort", "approval.respond" -> 15_000L
        else -> 20_000L
    }

internal fun agentReconnectDelayMillis(
    attempt: Int,
    jitterMillis: Long,
): Long {
    val exponent = attempt.coerceIn(0, 5)
    val base = (1_000L shl exponent).coerceAtMost(30_000L)
    return (base + jitterMillis.coerceIn(0L, base / 4)).coerceAtMost(30_000L)
}

internal fun appendCappedText(
    current: String,
    addition: String,
    limit: Int,
): String = (current + addition).takeLast(limit)

internal fun attachmentStoredName(
    sessionId: String,
    uploadId: String,
    index: Int,
    safeName: String,
): String = "${sessionId.take(8)}-$uploadId-${index + 1}-$safeName"

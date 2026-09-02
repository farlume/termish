package dev.termish.agent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.termish.data.Host
import dev.termish.data.HostRepository
import dev.termish.data.resolveCredentials
import dev.termish.ssh.SftpSession
import dev.termish.ssh.SshCallbacks
import dev.termish.ssh.SshConnection
import dev.termish.ssh.SshExecChannel
import dev.termish.ssh.SshSession
import dev.termish.ssh.createSftpSession
import dev.termish.ssh.createSshSession
import dev.termish.util.TermLog
import dev.termish.util.ioDispatcher
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val BRIDGE_PROTOCOL_VERSION = 3
private const val MINIMUM_BRIDGE_VERSION = "0.8.0"
private const val REMOTE_BRIDGE = "\$HOME/.local/share/termish-agent/current/termish-agent.pyz"

/**
 * 主机级原生 Agent 控制器。
 *
 * 控制面始终走独立 SSH：先探测/上传 Bridge，再通过无 PTY exec 打开 NDJSON
 * 长连接。远端 daemon 由 pyz 自行拉起，手机断开不会终止正在运行的 Agent。
 */
class AgentBridgeController(
    val host: Host,
    private val repository: HostRepository,
    private val callbacks: SshCallbacks,
    private val scope: CoroutineScope,
) {
    var state by mutableStateOf(AgentBridgeState.IDLE)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var installLog by mutableStateOf("")
        private set
    var bridgeVersion by mutableStateOf<String?>(null)
        private set
    var agents by mutableStateOf<List<AgentBridgeAgent>>(emptyList())
        private set
    var sessions by mutableStateOf<List<AgentBridgeSessionInfo>>(emptyList())
        private set
    var nativeSessions by mutableStateOf<List<AgentNativeSessionInfo>>(emptyList())
        private set
    var nativeHistoryLoading by mutableStateOf(false)
        private set
    var nativeHistoryImportingId by mutableStateOf<String?>(null)
        private set
    var currentSession by mutableStateOf<AgentBridgeSessionInfo?>(null)
        private set
    var messages by mutableStateOf<List<AgentChatMessage>>(emptyList())
        private set
    var pendingApprovals by mutableStateOf<List<AgentApprovalRequest>>(emptyList())
        private set
    var busy by mutableStateOf(false)
        private set

    /**
     * 仅标记由当前页面本次发送启动的 turn。
     *
     * 它与 [busy] 分离：历史会话可能仍处于远端运行/恢复状态，但加载已有消息时
     * 不应重新播放打字机动画。
     */
    var activeLocalTurnId by mutableStateOf<String?>(null)
        private set
    var installStatus by mutableStateOf(AgentInstallStatus())
        private set
    var attachmentProgress by mutableStateOf<String?>(null)
        private set
    var reconnecting by mutableStateOf(false)
        private set
    var hasOlderMessages by mutableStateOf(false)
        private set
    var loadingOlderMessages by mutableStateOf(false)
        private set
    var diagnostics by mutableStateOf(AgentDiagnostics())
        private set

    private val json = Json { ignoreUnknownKeys = true }
    private var ssh: SshSession? = null
    private var channel: SshExecChannel? = null
    private var readerJob: Job? = null
    private var reconnectJob: Job? = null
    private var streamFlushJob: Job? = null
    private val decoder = AgentNdjsonDecoder()
    private val streamBatcher = AgentStreamBatcher()
    private var nextRequestId = 1
    private val pending = mutableMapOf<Int, CompletableDeferred<JsonObject>>()
    private val eventCursors = mutableMapOf<String, AgentEventCursor>()
    private val recoveringEventSessions = mutableSetOf<String>()
    private val recoveryEventQueues = mutableMapOf<String, MutableList<JsonObject>>()

    /**
     * prompt.send 响应交给发送协程前，reader 仍可能继续消费同一批 NDJSON 事件。
     * 以 session 为粒度暂存这些事件，等用户消息/快照就位后再按序应用。
     */
    private val promptEventBuffer = AgentPromptEventBuffer()
    private var loadingSessionId: String? = null
    private var sessionLoadToken = 0
    private val sessionLoadEvents = mutableListOf<JsonObject>()
    private var oldestMessageSeq: Long? = null

    fun connect() {
        if (state == AgentBridgeState.CONNECTING || state == AgentBridgeState.INSTALLING) return
        state = AgentBridgeState.CONNECTING
        errorMessage = null
        scope.launch {
            try {
                ensureSsh()
                when (val probe = probeBridge()) {
                    ProbeResult.Missing -> state = AgentBridgeState.NEEDS_INSTALL
                    ProbeResult.NoPython -> state = AgentBridgeState.NO_PYTHON
                    is ProbeResult.Ready -> {
                        bridgeVersion = probe.version
                        if (probe.protocol != BRIDGE_PROTOCOL_VERSION || versionIsOlder(probe.version, MINIMUM_BRIDGE_VERSION)) {
                            state = AgentBridgeState.NEEDS_INSTALL
                        } else {
                            openProtocol()
                        }
                    }
                }
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    fun installBridge(bytes: ByteArray) {
        if (state == AgentBridgeState.INSTALLING) return
        state = AgentBridgeState.INSTALLING
        installLog = ""
        errorMessage = null
        scope.launch {
            try {
                val control = ensureSsh()
                val connection = connection()
                val sftp = withContext(ioDispatcher()) { createSftpSession(connection, callbacks) }
                uploadBridge(sftp, bytes)
                val installCommand =
                    "chmod 700 \"$REMOTE_BRIDGE.tmp\" && " +
                        "mv \"$REMOTE_BRIDGE.tmp\" \"$REMOTE_BRIDGE\" && " +
                        "python3 \"$REMOTE_BRIDGE\" restart"
                val result = withContext(ioDispatcher()) { control.runCommandDetailed(installCommand, 30_000) }
                if (result == null || result.exitCode != 0) {
                    throw IllegalStateException(result?.stderr?.ifBlank { result.stdout } ?: "Bridge install failed")
                }
                installLog = result.stdout.trim()
                val probe = probeBridge()
                if (
                    probe !is ProbeResult.Ready ||
                    probe.protocol != BRIDGE_PROTOCOL_VERSION ||
                    versionIsOlder(probe.version, MINIMUM_BRIDGE_VERSION)
                ) {
                    throw IllegalStateException("Bridge protocol verification failed")
                }
                bridgeVersion = probe.version
                openProtocol()
                installLog = ""
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    fun selectSession(id: String) {
        activeLocalTurnId = null
        flushStreamDeltas()
        val loadToken = ++sessionLoadToken
        loadingSessionId = id
        sessionLoadEvents.clear()
        scope.launch {
            try {
                val result = requestSessionPage(id)
                if (loadToken != sessionLoadToken) return@launch
                applySessionSnapshot(result)
                val queued = sessionLoadEvents.toList()
                loadingSessionId = null
                sessionLoadEvents.clear()
                queued.forEach { handleSequencedEnvelope(it) }
            } catch (e: Exception) {
                if (loadToken == sessionLoadToken) errorMessage = e.message
            } finally {
                if (loadToken == sessionLoadToken) {
                    loadingSessionId = null
                    sessionLoadEvents.clear()
                }
            }
        }
    }

    fun loadOlderMessages() {
        val session = currentSession ?: return
        val before = oldestMessageSeq ?: return
        if (!hasOlderMessages || loadingOlderMessages) return
        loadingOlderMessages = true
        scope.launch {
            try {
                val result = requestSessionPage(session.id, before)
                if (currentSession?.id != session.id) return@launch
                val knownIds =
                    messages
                        .asSequence()
                        .map { it.id }
                        .filter { it.isNotBlank() }
                        .toSet()
                val older = result.array("messages").mapNotNull(::parseMessage).filterNot { it.id in knownIds }
                messages = older + messages
                hasOlderMessages = result.boolean("hasMoreMessages")
                oldestMessageSeq = result.longOrNull("oldestMessageSeq") ?: oldestMessageSeq
                updateMessageDiagnostics()
            } catch (e: Exception) {
                errorMessage = e.message
            } finally {
                loadingOlderMessages = false
            }
        }
    }

    fun createSession(
        agent: String,
        cwd: String?,
        model: String? = null,
        provider: AgentProviderRuntime? = null,
    ) {
        scope.launch {
            try {
                val params =
                    buildJsonObject {
                        put("agent", agent)
                        if (!cwd.isNullOrBlank()) put("cwd", cwd.trim())
                        if (!model.isNullOrBlank()) put("model", model.trim())
                        put("provider", provider.toJson())
                    }
                val result = request("sessions.create", params)
                refreshSessions()
                selectSession(result.string("sessionId"))
            } catch (e: Exception) {
                errorMessage = e.message
            }
        }
    }

    fun startConversation(
        agent: String,
        cwd: String?,
        model: String?,
        provider: AgentProviderRuntime?,
        text: String,
        attachments: List<AgentPendingAttachment>,
        onAccepted: () -> Unit = {},
    ) {
        if (busy || text.isBlank()) return
        busy = true
        scope.launch {
            var sessionId: String? = null
            var accepted = false
            try {
                val params =
                    buildJsonObject {
                        put("agent", agent)
                        if (!cwd.isNullOrBlank()) put("cwd", cwd.trim())
                        if (!model.isNullOrBlank()) put("model", model.trim())
                        put("provider", provider.toJson())
                    }
                val created = request("sessions.create", params)
                sessionId = created.string("sessionId")
                val session = parseSession(created)
                beginPromptEventBuffer(sessionId)
                request(
                    "agents.check",
                    buildJsonObject {
                        put("sessionId", sessionId)
                        put("provider", provider.toJson())
                    },
                )
                val uploadedAttachments = uploadAttachments(session, attachments)
                val result =
                    request(
                        "prompt.send",
                        buildJsonObject {
                            put("sessionId", sessionId)
                            put("message", text)
                            put("attachments", uploadedAttachments.toJson())
                            put("provider", provider.toJson())
                        },
                    )
                accepted = true
                val turnId = result.string("turnId")
                onAccepted()
                val selected = requestSessionPage(sessionId)
                applySessionSnapshot(selected)
                activeLocalTurnId = turnId.takeIf { selected.boolean("busy") }
                drainPromptEventBuffer(sessionId)
                errorMessage = null
                refreshSessions()
            } catch (e: Exception) {
                busy = false
                activeLocalTurnId = null
                attachmentProgress = null
                val message = e.message ?: "Prompt failed"
                errorMessage = message
                if (!accepted) {
                    sessionId?.let { rejectedSessionId ->
                        runCatching {
                            request("sessions.delete", buildJsonObject { put("sessionId", rejectedSessionId) })
                        }
                        eventCursors.remove(rejectedSessionId)
                        recoveringEventSessions.remove(rejectedSessionId)
                        recoveryEventQueues.remove(rejectedSessionId)
                        discardPromptEventBuffer(rejectedSessionId)
                    }
                    refreshSessions()
                } else {
                    sessionId?.let(::discardPromptEventBuffer)
                    messages = messages + AgentChatMessage(role = "error", text = message, isError = true)
                    sessionId?.let(::selectSession)
                }
            }
        }
    }

    fun sendPrompt(
        text: String,
        attachments: List<AgentPendingAttachment> = emptyList(),
        provider: AgentProviderRuntime? = null,
        onAccepted: () -> Unit = {},
    ) {
        if (busy || text.isBlank()) return
        scope.launch { sendPromptInternal(text, attachments, provider, onAccepted) }
    }

    private suspend fun sendPromptInternal(
        text: String,
        pendingAttachments: List<AgentPendingAttachment>,
        provider: AgentProviderRuntime?,
        onAccepted: () -> Unit,
    ): Boolean {
        val selected = currentSession ?: return false
        if (busy || text.isBlank()) return false
        busy = true
        beginPromptEventBuffer(selected.id)
        try {
            request(
                "agents.check",
                buildJsonObject {
                    put("sessionId", selected.id)
                    put("provider", provider.toJson())
                },
            )
            val attachments = uploadAttachments(selected, pendingAttachments)
            val result =
                request(
                    "prompt.send",
                    buildJsonObject {
                        put("sessionId", selected.id)
                        put("message", text)
                        put("attachments", attachments.toJson())
                        put("provider", provider.toJson())
                    },
                )
            val turnId = result.string("turnId")
            activeLocalTurnId = turnId
            messages =
                messages +
                AgentChatMessage(
                    role = "user",
                    text = text,
                    id = "$turnId:user",
                    turnId = turnId,
                    createdAt = Clock.System.now().toEpochMilliseconds(),
                    attachments = attachments,
                )
            drainPromptEventBuffer(selected.id)
            errorMessage = null
            onAccepted()
            return true
        } catch (e: Exception) {
            busy = false
            activeLocalTurnId = null
            drainPromptEventBuffer(selected.id)
            attachmentProgress = null
            val message = e.message ?: "Prompt failed"
            errorMessage = message
            messages = messages + AgentChatMessage(role = "error", text = message, isError = true)
            return false
        }
    }

    fun respondApproval(
        approval: AgentApprovalRequest,
        decision: String,
        value: String? = null,
    ) {
        pendingApprovals = pendingApprovals.filterNot { it.id == approval.id }
        scope.launch {
            try {
                request(
                    "approval.respond",
                    buildJsonObject {
                        put("sessionId", approval.sessionId)
                        put("approvalId", approval.id)
                        put("decision", decision)
                        if (value != null) put("value", value)
                    },
                )
                errorMessage = null
            } catch (e: Exception) {
                if (approval.sessionId == currentSession?.id && pendingApprovals.none { it.id == approval.id }) {
                    pendingApprovals = pendingApprovals + approval
                }
                errorMessage = e.message
            }
        }
    }

    fun abort() {
        val selected = currentSession ?: return
        scope.launch {
            runCatching {
                request("prompt.abort", buildJsonObject { put("sessionId", selected.id) })
            }
        }
    }

    fun installAgent(agent: String) {
        installLog = ""
        installStatus = AgentInstallStatus(agent, AgentInstallPhase.INSTALLING)
        scope.launch {
            try {
                request("agents.install", buildJsonObject { put("agent", agent) })
            } catch (e: Exception) {
                errorMessage = e.message
            }
        }
    }

    fun renameSession(
        id: String,
        title: String,
    ) {
        scope.launch {
            try {
                request(
                    "sessions.rename",
                    buildJsonObject {
                        put("sessionId", id)
                        put("title", title)
                    },
                )
                refreshSessions()
                if (currentSession?.id == id) currentSession = currentSession?.copy(title = title.trim())
            } catch (e: Exception) {
                errorMessage = e.message
            }
        }
    }

    fun updateSessionModel(model: String?) {
        val selected = currentSession ?: return
        if (busy) return
        scope.launch {
            try {
                val result =
                    request(
                        "sessions.model",
                        buildJsonObject {
                            put("sessionId", selected.id)
                            put("model", model.orEmpty())
                        },
                    )
                currentSession = parseSession(result)
                refreshSessions()
                errorMessage = null
            } catch (e: Exception) {
                errorMessage = e.message
            }
        }
    }

    fun deleteSession(id: String) {
        scope.launch {
            try {
                request("sessions.delete", buildJsonObject { put("sessionId", id) })
                eventCursors.remove(id)
                recoveringEventSessions.remove(id)
                recoveryEventQueues.remove(id)
                if (currentSession?.id == id) newChat()
                refreshSessions()
            } catch (e: Exception) {
                errorMessage = e.message
            }
        }
    }

    fun loadNativeHistory() {
        if (nativeHistoryLoading) return
        nativeHistoryLoading = true
        scope.launch {
            try {
                val result = request("sessions.nativeList")
                nativeSessions =
                    result.array("sessions").mapNotNull { element ->
                        val value = element as? JsonObject ?: return@mapNotNull null
                        val nativeId = value.string("nativeId")
                        val agent = value.string("agent")
                        if (nativeId.isBlank() || agent.isBlank()) return@mapNotNull null
                        AgentNativeSessionInfo(
                            nativeId = nativeId,
                            agent = agent,
                            title = value.string("title"),
                            cwd = value.string("cwd"),
                            messageCount = value.int("messageCount"),
                            updatedAt = value.long("updatedAt"),
                            importedSessionId = value.stringOrNull("importedSessionId"),
                        )
                    }
                errorMessage = null
            } catch (e: Exception) {
                errorMessage = e.message
            } finally {
                nativeHistoryLoading = false
            }
        }
    }

    fun importNativeHistory(
        native: AgentNativeSessionInfo,
        onComplete: (Boolean) -> Unit,
    ) {
        if (nativeHistoryImportingId != null) return
        nativeHistoryImportingId = native.nativeId
        scope.launch {
            try {
                val imported =
                    request(
                        "sessions.nativeImport",
                        buildJsonObject {
                            put("agent", native.agent)
                            put("nativeId", native.nativeId)
                        },
                    )
                refreshSessions()
                val selected = requestSessionPage(imported.string("sessionId"))
                applySessionSnapshot(selected)
                nativeSessions =
                    nativeSessions.map {
                        if (it.agent == native.agent && it.nativeId == native.nativeId) {
                            it.copy(importedSessionId = imported.string("sessionId"))
                        } else {
                            it
                        }
                    }
                errorMessage = null
                onComplete(true)
            } catch (e: Exception) {
                errorMessage = e.message
                onComplete(false)
            } finally {
                nativeHistoryImportingId = null
            }
        }
    }

    fun browseDirectories(
        path: String?,
        onResult: (String, List<AgentDirectory>, String?) -> Unit,
    ) {
        scope.launch {
            var sftp: SftpSession? = null
            try {
                val opened = withContext(ioDispatcher()) { createSftpSession(connection(), callbacks) }
                sftp = opened
                val home = withContext(ioDispatcher()) { opened.home() }
                val target = path?.takeIf { it.isNotBlank() } ?: home
                val entries =
                    withContext(ioDispatcher()) {
                        opened
                            .list(target)
                            .asSequence()
                            .filter { it.isDirectory && it.name != "." && it.name != ".." }
                            .sortedWith(compareBy({ it.isHidden }, { it.name.lowercase() }))
                            .map { AgentDirectory(it.name, joinRemotePath(target, it.name)) }
                            .toList()
                    }
                onResult(target, entries, null)
            } catch (e: Exception) {
                onResult(path.orEmpty(), emptyList(), e.message)
            } finally {
                withContext(ioDispatcher()) { sftp?.close() }
            }
        }
    }

    /**
     * 为 Agent 页面打开一个独立的 SFTP 会话（文件管理 / 上传用）。
     * 认证 / 主机密钥确认复用本控制器的回调（弹窗由 Agent 页面全局处理）；
     * 调用方负责在结束时 close（与 [browseDirectories] 同模式）。
     */
    suspend fun openSftp(): SftpSession =
        withContext(ioDispatcher()) {
            createSftpSession(connection(), callbacks)
        }

    /**
     * 在已认证的控制连接上执行一次性远端命令（Agent Git 面板等用）。
     * 与 Agent 对话互不干扰（独立 exec 通道）；失败/未连接返回 null。
     */
    suspend fun runRemoteCommand(
        command: String,
        timeoutMs: Long = 20_000,
    ): String? =
        runCatching {
            withContext(ioDispatcher()) {
                ensureSsh().runCommandDetailed(command, timeoutMs)?.stdout
            }
        }.getOrNull()

    fun newChat() {
        flushStreamDeltas()
        sessionLoadToken += 1
        loadingSessionId = null
        sessionLoadEvents.clear()
        currentSession = null
        messages = emptyList()
        pendingApprovals = emptyList()
        busy = false
        activeLocalTurnId = null
        hasOlderMessages = false
        loadingOlderMessages = false
        oldestMessageSeq = null
        updateMessageDiagnostics()
    }

    /**
     * 拉取供应商模型列表（GET /models）。经远端 Bridge 转发：手机侧无需 CORS，
     * 也不暴露 key 给第三方服务。结果经 [onResult] 回传（失败含原因）。
     */
    fun fetchProviderModels(
        baseUrl: String,
        apiKey: String,
        type: String,
        onResult: (Result<List<String>>) -> Unit,
    ) {
        scope.launch {
            val result =
                runCatching {
                    request(
                        "providers.fetchModels",
                        buildJsonObject {
                            put("baseUrl", baseUrl)
                            put("apiKey", apiKey)
                            put("type", type)
                        },
                    ).array("models").mapNotNull { it.jsonPrimitive.contentOrNull }
                }
            onResult(result)
        }
    }

    fun close() {
        // Mark the controller closed before tearing down the raw channel. A blocked
        // reader may wake up synchronously from close(); it must not interpret that
        // intentional EOF as a disconnect and start a new SSH connection.
        state = AgentBridgeState.IDLE
        reconnectJob?.cancel()
        reconnectJob = null
        reconnecting = false
        streamFlushJob?.cancel()
        streamFlushJob = null
        streamBatcher.clear()
        activeLocalTurnId = null
        sessionLoadToken += 1
        loadingSessionId = null
        sessionLoadEvents.clear()
        recoveringEventSessions.clear()
        recoveryEventQueues.clear()
        promptEventBuffer.clear()
        readerJob?.cancel()
        readerJob = null
        val activeChannel = channel
        channel = null
        activeChannel?.close()
        ssh?.close()
        ssh = null
        failPending(IllegalStateException("Agent Bridge closed"))
    }

    private suspend fun ensureSsh(): SshSession {
        ssh?.takeIf { it.isActive() }?.let { return it }
        val created = createSshSession(connection(), callbacks)
        val info =
            withContext(ioDispatcher()) { created.connectAuthOnly() }
                ?: throw IllegalStateException("SSH control connection is unavailable")
        info.hostKey?.let { repository.touchConnected(host.id, it.fingerprintSha256) }
        ssh = created
        return created
    }

    private fun connection(): SshConnection {
        val (password, privateKey) = resolveAgentCredentials(host)
        return SshConnection(
            host = host.hostname,
            port = host.port,
            username = host.username,
            password = password,
            privateKeyPem = privateKey,
            keepAliveSeconds = repository.loadSettings().keepaliveSeconds,
        )
    }

    private suspend fun probeBridge(): ProbeResult {
        val command =
            "if ! command -v python3 >/dev/null 2>&1; then echo NO_PYTHON; " +
                "elif [ ! -f \"$REMOTE_BRIDGE\" ]; then echo MISSING; " +
                "else python3 \"$REMOTE_BRIDGE\" status; fi"
        val result =
            withContext(ioDispatcher()) { ensureSsh().runCommandDetailed(command, 10_000) }
                ?: throw IllegalStateException("Unable to inspect Agent Bridge")
        val output =
            result.stdout
                .trim()
                .lineSequence()
                .lastOrNull()
                .orEmpty()
        return when (output) {
            "NO_PYTHON" -> ProbeResult.NoPython
            "MISSING", "" -> ProbeResult.Missing
            else -> {
                val value = json.parseToJsonElement(output).jsonObject
                ProbeResult.Ready(
                    version = value.string("version"),
                    protocol = value.int("protocolVersion"),
                )
            }
        }
    }

    private suspend fun uploadBridge(
        sftp: SftpSession,
        bytes: ByteArray,
    ) {
        try {
            val home = withContext(ioDispatcher()) { sftp.home() }
            val directory = "$home/.local/share/termish-agent/current"
            val mkdirResult =
                withContext(ioDispatcher()) {
                    ensureSsh().runCommandDetailed("umask 077; mkdir -p ${shellQuote(directory)}", 10_000)
                }
            if (mkdirResult == null || mkdirResult.exitCode != 0) {
                throw IllegalStateException(mkdirResult?.stderr ?: "Unable to create Bridge directory")
            }
            var sent = false
            withContext(ioDispatcher()) {
                sftp.upload(
                    remotePath = "$directory/termish-agent.pyz.tmp",
                    totalSize = bytes.size.toLong(),
                    onProgress = { uploaded, _ -> installLog = "$uploaded / ${bytes.size} bytes" },
                    nextChunk = {
                        if (sent) {
                            null
                        } else {
                            sent = true
                            bytes
                        }
                    },
                )
            }
        } finally {
            withContext(ioDispatcher()) { sftp.close() }
        }
    }

    private suspend fun openProtocol() {
        val previousChannel = channel
        channel = null
        readerJob?.cancel()
        readerJob = null
        previousChannel?.close()
        val opened =
            withContext(ioDispatcher()) {
                ensureSsh().startExecRaw("python3 \"$REMOTE_BRIDGE\" connect")
            } ?: throw IllegalStateException("Unable to open Agent Bridge channel")
        channel = opened
        decoder.reset()
        readerJob =
            scope.launch {
                try {
                    while (true) {
                        val chunk = withContext(ioDispatcher()) { opened.read() } ?: break
                        consume(chunk)
                    }
                    if (channel === opened && state == AgentBridgeState.READY) {
                        scheduleReconnect("Agent Bridge disconnected")
                    }
                } catch (e: Exception) {
                    if (channel === opened && state == AgentBridgeState.READY) {
                        scheduleReconnect(e.message ?: "Agent Bridge disconnected")
                    }
                }
            }
        request(
            "system.hello",
            buildJsonObject {
                put("protocolMin", BRIDGE_PROTOCOL_VERSION)
                put("protocolMax", BRIDGE_PROTOCOL_VERSION)
            },
        )
        coroutineScope {
            val agentsRefresh = async { refreshAgents() }
            val sessionsRefresh = async { refreshSessions() }
            agentsRefresh.await()
            sessionsRefresh.await()
        }
        state = AgentBridgeState.READY
        reconnecting = false
        diagnostics = diagnostics.copy(reconnectAttempt = 0)
    }

    private fun scheduleReconnect(reason: String) {
        if (reconnectJob?.isActive == true || state != AgentBridgeState.READY) return
        val selectedSessionId = currentSession?.id
        reconnecting = true
        errorMessage = reason
        val disconnectedChannel = channel
        channel = null
        disconnectedChannel?.close()
        readerJob = null
        failPending(IllegalStateException(reason))
        ssh?.close()
        ssh = null
        reconnectJob =
            scope.launch {
                var attempt = 0
                while (state == AgentBridgeState.READY && channel == null) {
                    diagnostics = diagnostics.copy(reconnectAttempt = attempt + 1)
                    val base = (1_000L shl attempt.coerceIn(0, 5)).coerceAtMost(30_000L)
                    val retryDelay = agentReconnectDelayMillis(attempt, Random.nextLong(0L, base / 4 + 1))
                    TermLog.i("agent") { "bridge reconnect attempt=${attempt + 1} delayMs=$retryDelay" }
                    delay(retryDelay)
                    try {
                        ensureSsh()
                        val probe = probeBridge()
                        if (
                            probe !is ProbeResult.Ready ||
                            probe.protocol != BRIDGE_PROTOCOL_VERSION ||
                            versionIsOlder(probe.version, MINIMUM_BRIDGE_VERSION)
                        ) {
                            state = AgentBridgeState.NEEDS_INSTALL
                            reconnecting = false
                            reconnectJob = null
                            return@launch
                        }
                        bridgeVersion = probe.version
                        openProtocol()
                        selectedSessionId?.let { recoverSessionEvents(it) }
                        errorMessage = null
                        diagnostics =
                            diagnostics.copy(
                                reconnectAttempt = 0,
                                reconnectCount = diagnostics.reconnectCount + 1,
                            )
                        TermLog.i("agent") { "bridge reconnected attempt=${attempt + 1}" }
                        reconnectJob = null
                        return@launch
                    } catch (e: Exception) {
                        TermLog.w("agent") { "bridge reconnect failed attempt=${attempt + 1}: ${e.message}" }
                        errorMessage = e.message ?: reason
                        channel?.close()
                        channel = null
                        ssh?.close()
                        ssh = null
                    }
                    attempt += 1
                }
                reconnectJob = null
                reconnecting = false
            }
    }

    private suspend fun refreshAgents() {
        val result = request("agents.list")
        agents =
            result.array("agents").map { element ->
                val value = element.jsonObject
                AgentBridgeAgent(
                    id = value.string("id"),
                    label = value.string("label"),
                    available = value.boolean("available"),
                    supported = value.boolean("supported"),
                )
            }
    }

    private suspend fun refreshSessions() {
        val result = request("sessions.list")
        sessions = result.array("sessions").map { parseSession(it.jsonObject) }
    }

    private fun applySessionSnapshot(result: JsonObject) {
        flushStreamDeltas()
        val session = parseSession(result)
        currentSession = session
        busy = result.boolean("busy")
        messages = result.array("messages").mapNotNull(::parseMessage)
        pendingApprovals = result.array("approvals").mapNotNull(::parseApproval)
        hasOlderMessages = result.boolean("hasMoreMessages")
        oldestMessageSeq = result.longOrNull("oldestMessageSeq")
        updateMessageDiagnostics()
        val epoch = result.stringOrNull("eventEpoch").orEmpty()
        val sequence = result.long("eventCursor")
        if (epoch.isBlank()) {
            eventCursors.remove(session.id)
        } else {
            eventCursors[session.id] = AgentEventCursor(epoch, sequence)
        }
    }

    private fun scheduleEventRecovery(sessionId: String) {
        if (sessionId != currentSession?.id || !recoveringEventSessions.add(sessionId)) return
        recoveryEventQueues[sessionId] = mutableListOf()
        scope.launch {
            runCatching { recoverMarkedSessionEvents(sessionId) }
                .onFailure { errorMessage = it.message }
        }
    }

    private suspend fun recoverSessionEvents(sessionId: String) {
        if (sessionId != currentSession?.id || !recoveringEventSessions.add(sessionId)) return
        recoveryEventQueues[sessionId] = mutableListOf()
        recoverMarkedSessionEvents(sessionId)
    }

    private suspend fun recoverMarkedSessionEvents(sessionId: String) {
        try {
            val cursor = eventCursors[sessionId]
            if (cursor == null) {
                reloadSessionSnapshot(sessionId)
                return
            }
            val result =
                request(
                    "events.replay",
                    buildJsonObject {
                        put("sessionId", sessionId)
                        put("eventEpoch", cursor.epoch)
                        put("after", cursor.sequence)
                    },
                )
            if (sessionId != currentSession?.id) return
            val reset = result.boolean("reset")
            val replayEpoch = result.stringOrNull("eventEpoch").orEmpty()
            val replayCursor = result.long("eventCursor")
            val replayApplied =
                !reset &&
                    result.array("events").all { element ->
                        val envelope = element as? JsonObject ?: return@all false
                        handleSequencedEnvelope(envelope, recoverOnGap = false)
                    }
            val caughtUp = eventCursors[sessionId] == AgentEventCursor(replayEpoch, replayCursor)
            if (!replayApplied || !caughtUp) reloadSessionSnapshot(sessionId)
        } finally {
            val queued = recoveryEventQueues.remove(sessionId).orEmpty()
            recoveringEventSessions.remove(sessionId)
            queued
                .sortedBy { it.long("eventSeq") }
                .forEach { handleSequencedEnvelope(it) }
        }
    }

    private suspend fun reloadSessionSnapshot(sessionId: String) {
        val loadedLimit = maxOf(AGENT_MESSAGE_PAGE_SIZE, messages.count { !it.running })
        val result = requestSessionPage(sessionId, limit = loadedLimit)
        if (sessionId == currentSession?.id) applySessionSnapshot(result)
    }

    private suspend fun requestSessionPage(
        sessionId: String,
        beforeSeq: Long? = null,
        limit: Int = AGENT_MESSAGE_PAGE_SIZE,
    ): JsonObject =
        request(
            "sessions.get",
            buildJsonObject {
                put("sessionId", sessionId)
                put("messageLimit", limit.coerceAtMost(300))
                if (beforeSeq != null) put("beforeSeq", beforeSeq)
            },
        )

    private fun beginPromptEventBuffer(sessionId: String) {
        promptEventBuffer.begin(sessionId)
    }

    private fun drainPromptEventBuffer(sessionId: String) {
        promptEventBuffer.drain(sessionId).forEach(::handleSequencedEnvelope)
    }

    private fun discardPromptEventBuffer(sessionId: String) {
        promptEventBuffer.discard(sessionId)
    }

    private suspend fun request(
        method: String,
        params: JsonObject = JsonObject(emptyMap()),
    ): JsonObject {
        val active = channel ?: throw IllegalStateException("Agent Bridge is not connected")
        val id = nextRequestId++
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred
        val request =
            buildJsonObject {
                put("id", id)
                put("method", method)
                put("params", params)
            }
        active.write((request.toString() + "\n").encodeToByteArray())
        val startedAt = Clock.System.now().toEpochMilliseconds()
        return try {
            withTimeout(agentRequestTimeoutMillis(method)) { deferred.await() }
        } finally {
            pending.remove(id)
            val latency = Clock.System.now().toEpochMilliseconds() - startedAt
            diagnostics =
                diagnostics.copy(
                    lastRequestLatencyMs = latency,
                    lastRequestMethod = method,
                )
            TermLog.d("agent") { "bridge request method=$method latencyMs=$latency" }
        }
    }

    private fun consume(chunk: ByteArray) {
        decoder.append(chunk).forEach(::handleLine)
    }

    private fun handleLine(line: String) {
        val value =
            runCatching { json.parseToJsonElement(line).jsonObject }.getOrElse {
                TermLog.w("agent") { "invalid bridge line: ${line.take(160)}" }
                return
            }
        value["id"]?.jsonPrimitive?.intOrNull?.let { id ->
            val deferred = pending[id] ?: return
            val error = value["error"] as? JsonObject
            if (error != null) {
                deferred.completeExceptionally(IllegalStateException(error.string("message")))
            } else {
                deferred.complete((value["result"] as? JsonObject) ?: JsonObject(emptyMap()))
            }
            return
        }
        when (value.stringOrNull("type")) {
            "busy", "event" -> {
                val sessionId = value.stringOrNull("sessionId")
                when {
                    promptEventBuffer.enqueue(sessionId, value) -> Unit
                    sessionId != null && sessionId == loadingSessionId -> sessionLoadEvents += value
                    sessionId != null && sessionId in recoveringEventSessions ->
                        recoveryEventQueues.getOrPut(sessionId, ::mutableListOf) += value
                    else -> handleSequencedEnvelope(value)
                }
            }
            "session_deleted" -> scope.launch { refreshSessions() }
        }
    }

    private fun handleSequencedEnvelope(
        envelope: JsonObject,
        recoverOnGap: Boolean = true,
    ): Boolean {
        val sessionId = envelope.stringOrNull("sessionId")
        val epoch = envelope.stringOrNull("eventEpoch").orEmpty()
        val sequence = envelope.long("eventSeq")
        if (sessionId != null && sessionId == currentSession?.id) {
            when (classifyAgentEvent(eventCursors[sessionId], epoch, sequence)) {
                AgentEventDecision.DUPLICATE -> return true
                AgentEventDecision.GAP, AgentEventDecision.RESET -> {
                    if (recoverOnGap) scheduleEventRecovery(sessionId)
                    return false
                }
                AgentEventDecision.APPLY -> {
                    if (epoch.isNotBlank() && sequence > 0L) {
                        eventCursors[sessionId] = AgentEventCursor(epoch, sequence)
                    }
                }
            }
        }
        when (envelope.stringOrNull("type")) {
            "busy" -> {
                if (sessionId == currentSession?.id) {
                    busy = envelope.boolean("busy")
                    if (!busy) activeLocalTurnId = null
                }
            }
            "event" -> handleEvent(envelope)
        }
        return true
    }

    private fun handleEvent(envelope: JsonObject) {
        val event = envelope["event"] as? JsonObject ?: return
        val sessionId = envelope.stringOrNull("sessionId")
        val type = event.stringOrNull("type") ?: return
        if (sessionId != null && sessionId != currentSession?.id) return
        if (type != "delta" && type != "thinking_delta") flushStreamDeltas()
        when (type) {
            "delta" -> appendStream("assistant", event.string("text"), event)
            // Do not render a card until the agent provides actual reasoning text.
            "thinking_start" -> Unit
            "thinking_delta" -> appendStream("thinking", event.string("text"), event)
            "thinking" -> replaceOrAppend("thinking", event.string("text"), event)
            "assistant_message" -> replaceOrAppend("assistant", event.string("text"), event)
            "tool_start" -> startTool(event)
            "tool_end" -> finishTool(event)
            "approval_request" -> {
                val approval = parseApproval(event) ?: return
                pendingApprovals = pendingApprovals.filterNot { it.id == approval.id } + approval
            }
            "approval_resolved" -> {
                val approvalId = event.string("approvalId")
                pendingApprovals = pendingApprovals.filterNot { it.id == approvalId }
            }
            "error" ->
                messages =
                    messages +
                    AgentChatMessage(
                        role = "error",
                        text = event.string("message"),
                        id = event.string("activityId").ifBlank { "${event.string("turnId")}:error" },
                        turnId = event.string("turnId"),
                        isError = true,
                        createdAt = event.long("ts"),
                    )
            "cancelled" -> {
                busy = false
                activeLocalTurnId = null
            }
            "settled" -> {
                busy = false
                activeLocalTurnId = null
                val completedAt = Clock.System.now().toEpochMilliseconds()
                messages =
                    messages.map {
                        if (it.running) it.copy(running = false, completedAt = completedAt) else it
                    }
                scope.launch { refreshSessions() }
            }
            "install_output" -> {
                installLog = appendCappedText(installLog, event.string("text"), AGENT_INSTALL_LOG_LIMIT)
                installStatus =
                    AgentInstallStatus(
                        event.stringOrNull("agent"),
                        AgentInstallPhase.INSTALLING,
                        installLog.takeLast(2_000),
                    )
            }
            "install_complete" -> {
                val agent = event.stringOrNull("agent")
                installStatus = AgentInstallStatus(agent, AgentInstallPhase.SUCCEEDED, installLog.trim())
                scope.launch { refreshAgents() }
            }
            "install_error" -> {
                val message = event.string("message")
                errorMessage = message
                installLog =
                    appendCappedText(
                        installLog,
                        if (installLog.isBlank()) message else "\n$message",
                        AGENT_INSTALL_LOG_LIMIT,
                    )
                installStatus = AgentInstallStatus(event.stringOrNull("agent"), AgentInstallPhase.FAILED, message)
            }
        }
        updateMessageDiagnostics()
    }

    private fun appendStream(
        role: String,
        text: String,
        event: JsonObject,
    ) {
        if (text.isEmpty()) return
        streamBatcher.append(
            role = role,
            activityId = event.stringOrNull("activityId"),
            turnId = event.string("turnId"),
            createdAt = event.long("ts"),
            text = text,
        )
        if (streamFlushJob?.isActive != true) {
            streamFlushJob =
                scope.launch {
                    delay(AGENT_STREAM_FLUSH_INTERVAL_MS)
                    flushStreamDeltas()
                }
        }
    }

    private fun flushStreamDeltas() {
        streamFlushJob?.cancel()
        streamFlushJob = null
        val deltas = streamBatcher.drain()
        if (deltas.isEmpty()) return
        val items = messages.toMutableList()
        val indexes = mutableMapOf<Pair<String, String?>, Int>()
        items.forEachIndexed { index, message ->
            if (message.running) indexes[message.role to message.activityId] = index
        }
        deltas.forEach { delta ->
            val key = delta.role to delta.activityId
            val index = indexes[key]
            if (index != null) {
                val previous = items[index]
                items[index] = previous.copy(text = previous.text + delta.text)
            } else {
                indexes[key] = items.size
                items +=
                    AgentChatMessage(
                        role = delta.role,
                        text = delta.text,
                        id = delta.activityId.orEmpty(),
                        turnId = delta.turnId,
                        activityId = delta.activityId,
                        running = true,
                        createdAt = delta.createdAt,
                    )
            }
        }
        messages = items
        updateMessageDiagnostics()
    }

    private fun replaceOrAppend(
        role: String,
        text: String,
        event: JsonObject,
    ) {
        if (text.isEmpty()) return
        if (role == "assistant") finishRunningThinking(event.string("turnId"), event.long("ts"))
        val activityId = event.stringOrNull("activityId")
        val index = messages.indexOfLast { it.role == role && it.activityId == activityId }
        messages =
            if (index >= 0) {
                messages.toMutableList().also {
                    it[index] = it[index].copy(text = text, running = false, completedAt = event.long("ts"))
                }
            } else {
                messages +
                    AgentChatMessage(
                        role = role,
                        text = text,
                        id = activityId.orEmpty(),
                        turnId = event.string("turnId"),
                        activityId = activityId,
                        createdAt = event.long("ts"),
                        completedAt = event.long("ts"),
                    )
            }
    }

    private fun startTool(event: JsonObject) {
        val turnId = event.string("turnId")
        finishRunningThinking(turnId, event.long("ts"))
        messages =
            messages +
            AgentChatMessage(
                role = "tool",
                text = "",
                id = event.string("activityId"),
                turnId = turnId,
                activityId = event.stringOrNull("activityId"),
                toolName = event.stringOrNull("name"),
                toolInput = event.stringOrNull("args"),
                running = true,
                createdAt = event.long("ts"),
            )
    }

    private fun finishRunningThinking(
        turnId: String,
        completedAt: Long,
    ) {
        messages =
            messages.map {
                if (it.role == "thinking" && it.turnId == turnId && it.running) {
                    it.copy(running = false, completedAt = completedAt)
                } else {
                    it
                }
            }
    }

    private fun finishTool(event: JsonObject) {
        val name = event.stringOrNull("name")
        val activityId = event.stringOrNull("activityId")
        val index =
            messages.indexOfLast {
                it.role == "tool" && it.running && (it.activityId == activityId || activityId == null && it.toolName == name)
            }
        val started = messages.getOrNull(index)
        val finished =
            AgentChatMessage(
                role = "tool",
                text = event.string("output"),
                id = activityId.orEmpty(),
                turnId = event.string("turnId"),
                activityId = activityId,
                toolName = name,
                toolInput = event.stringOrNull("toolInput") ?: started?.toolInput,
                isError = event.boolean("isError"),
                createdAt = started?.createdAt ?: event.long("startedAt"),
                completedAt = event.long("completedAt").takeIf { it > 0L } ?: event.long("ts"),
                artifacts = parseArtifacts(event),
            )
        messages =
            if (index >= 0) {
                messages.toMutableList().also { it[index] = finished }
            } else {
                messages + finished
            }
    }

    private fun parseSession(value: JsonObject): AgentBridgeSessionInfo =
        AgentBridgeSessionInfo(
            id = value.string("sessionId"),
            agent = value.string("agent"),
            title = value.string("title"),
            cwd = value.string("cwd"),
            busy = value.boolean("busy"),
            messageCount = value.int("messageCount"),
            model = value.stringOrNull("model"),
            provider = value.stringOrNull("provider"),
            waitingApproval = value.boolean("waitingApproval"),
        )

    private fun parseApproval(element: JsonElement): AgentApprovalRequest? {
        val value = element as? JsonObject ?: return null
        val id = value.string("approvalId").ifBlank { value.string("id") }
        if (id.isBlank()) return null
        return AgentApprovalRequest(
            id = id,
            sessionId = value.string("sessionId"),
            turnId = value.string("turnId"),
            agent = value.string("agent"),
            kind = value.string("kind"),
            title = value.string("title"),
            message = value.string("message"),
            command = value.string("command"),
            cwd = value.string("cwd"),
            details = value.string("details"),
            placeholder = value.string("placeholder"),
            prefill = value.string("prefill"),
            options = value.array("options").mapNotNull { it.jsonPrimitive.contentOrNull },
            secret = value.boolean("secret"),
            allowCustom = value.boolean("allowCustom"),
            timeoutMs = value.long("timeoutMs"),
        )
    }

    private fun parseMessage(element: JsonElement): AgentChatMessage? {
        val value = element as? JsonObject ?: return null
        return AgentChatMessage(
            role = value.string("role"),
            text = value.string("text"),
            id = value.string("messageId"),
            turnId = value.string("turnId"),
            activityId = value.stringOrNull("activityId"),
            agent = value.stringOrNull("agent"),
            toolName = value.stringOrNull("toolName"),
            toolInput = value.stringOrNull("toolInput"),
            isError = value.boolean("isError"),
            running = value.boolean("running"),
            createdAt = value.long("startedAt").takeIf { it > 0L } ?: value.long("ts"),
            completedAt = value.long("completedAt").takeIf { it > 0L },
            attachments =
                value.array("attachments").mapNotNull { attachment ->
                    val item = attachment as? JsonObject ?: return@mapNotNull null
                    AgentAttachment(
                        name = item.string("name"),
                        remotePath = item.string("path"),
                        size = item.long("size"),
                    )
                },
            artifacts = parseArtifacts(value),
        )
    }

    private fun parseArtifacts(value: JsonObject): List<AgentArtifact> =
        value.array("artifacts").mapNotNull { artifact ->
            val item = artifact as? JsonObject ?: return@mapNotNull null
            val path = item.string("path")
            if (path.isBlank()) return@mapNotNull null
            AgentArtifact(
                name = item.string("name").ifBlank { path.substringAfterLast('/') },
                remotePath = path,
                size = item.long("size"),
                kind = item.string("kind"),
            )
        }

    private suspend fun uploadAttachments(
        session: AgentBridgeSessionInfo,
        attachments: List<AgentPendingAttachment>,
    ): List<AgentAttachment> {
        if (attachments.isEmpty()) return emptyList()
        val seenSourceIds = mutableSetOf<String>()
        val uniqueAttachments =
            attachments.filter { attachment ->
                seenSourceIds.add(attachment.sourceId)
            }
        val relativeDirectory = ".termish/attachments"
        val remoteDirectory = joinRemotePath(session.cwd, relativeDirectory)
        val mkdir =
            withContext(ioDispatcher()) {
                ensureSsh().runCommandDetailed("umask 077; mkdir -p ${shellQuote(remoteDirectory)}", 10_000)
            }
        if (mkdir == null || mkdir.exitCode != 0) {
            throw IllegalStateException(mkdir?.stderr ?: "Unable to create attachment directory")
        }
        val sftp = withContext(ioDispatcher()) { createSftpSession(connection(), callbacks) }
        val uploadId =
            Clock.System
                .now()
                .toEpochMilliseconds()
                .toString(36) +
                Random.nextInt().toUInt().toString(36)
        return try {
            uniqueAttachments.mapIndexed { index, pending ->
                val safeName = sanitizeFileName(pending.name)
                val storedName = attachmentStoredName(session.id, uploadId, index, safeName)
                val relativePath = "$relativeDirectory/$storedName"
                attachmentProgress = "${index + 1} / ${uniqueAttachments.size} · ${pending.name}"
                val reader = pending.openReader?.invoke()
                try {
                    withContext(ioDispatcher()) {
                        sftp.upload(
                            remotePath = joinRemotePath(session.cwd, relativePath),
                            totalSize = pending.size,
                            onProgress = { sent, total ->
                                val percent = if (total > 0) (sent * 100 / total).toInt() else 0
                                attachmentProgress = "${index + 1} / ${uniqueAttachments.size} · $percent%"
                            },
                            nextChunk = reader?.readChunk ?: pending.readChunk,
                        )
                    }
                } finally {
                    reader?.close?.invoke()
                }
                AgentAttachment(pending.name, relativePath, pending.size)
            }
        } finally {
            withContext(ioDispatcher()) { sftp.close() }
            attachmentProgress = null
        }
    }

    private fun fail(error: Exception) {
        flushStreamDeltas()
        TermLog.e("agent") { "bridge ${host.name}: ${error.message}" }
        errorMessage = error.message ?: "Agent Bridge failed"
        reconnecting = false
        activeLocalTurnId = null
        state = AgentBridgeState.ERROR
    }

    private fun failPending(error: Exception) {
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
    }

    private fun updateMessageDiagnostics() {
        diagnostics =
            diagnostics.copy(
                loadedMessageCount = messages.size,
                totalMessageCount = currentSession?.messageCount ?: 0,
            )
    }
}

private sealed interface ProbeResult {
    data object Missing : ProbeResult

    data object NoPython : ProbeResult

    data class Ready(
        val version: String,
        val protocol: Int,
    ) : ProbeResult
}

private fun resolveAgentCredentials(host: Host): Pair<String?, String?> = resolveCredentials(host)

private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

private fun versionIsOlder(
    actual: String,
    required: String,
): Boolean {
    val actualParts = actual.split('.').map { it.toIntOrNull() ?: 0 }
    val requiredParts = required.split('.').map { it.toIntOrNull() ?: 0 }
    val size = maxOf(actualParts.size, requiredParts.size)
    for (index in 0 until size) {
        val left = actualParts.getOrElse(index) { 0 }
        val right = requiredParts.getOrElse(index) { 0 }
        if (left != right) return left < right
    }
    return false
}

private fun JsonObject.string(name: String): String = this[name]?.jsonPrimitive?.contentOrNull.orEmpty()

private fun JsonObject.stringOrNull(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

private fun JsonObject.boolean(name: String): Boolean = this[name]?.jsonPrimitive?.booleanOrNull ?: false

private fun JsonObject.int(name: String): Int = this[name]?.jsonPrimitive?.intOrNull ?: 0

private fun JsonObject.long(name: String): Long = this[name]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L

private fun JsonObject.longOrNull(name: String): Long? = this[name]?.jsonPrimitive?.contentOrNull?.toLongOrNull()

private fun JsonObject.array(name: String): JsonArray = (this[name] as? JsonArray) ?: JsonArray(emptyList())

private fun List<AgentAttachment>.toJson(): JsonArray =
    buildJsonArray {
        this@toJson.forEach { attachment ->
            add(
                buildJsonObject {
                    put("name", attachment.name)
                    put("path", attachment.remotePath)
                    put("size", attachment.size)
                },
            )
        }
    }

private fun AgentProviderRuntime?.toJson(): JsonObject =
    buildJsonObject {
        this@toJson ?: return@buildJsonObject
        put("id", id)
        put("type", type)
        put("apiKey", apiKey)
        put("baseUrl", baseUrl)
        put("anthropicBaseUrl", anthropicBaseUrl)
        put("piProvider", piProvider)
    }

private fun joinRemotePath(
    base: String,
    child: String,
): String = if (base == "/") "/${child.trimStart('/')}" else "${base.trimEnd('/')}/${child.trimStart('/')}"

private fun sanitizeFileName(name: String): String {
    val clean = name.map { character -> if (character.isLetterOrDigit() || character in "._-") character else '_' }.joinToString("")
    return clean.trim('.').ifBlank { "attachment" }.take(120)
}

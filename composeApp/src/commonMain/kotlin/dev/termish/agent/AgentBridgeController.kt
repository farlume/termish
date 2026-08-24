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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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

private const val BRIDGE_PROTOCOL_VERSION = 2
private const val MINIMUM_BRIDGE_VERSION = "0.4.0"
private const val REMOTE_BRIDGE = "\$HOME/.local/share/termish-agent/current/termish-agent.pyz"
private const val REQUEST_TIMEOUT_MS = 20_000L

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
    var currentSession by mutableStateOf<AgentBridgeSessionInfo?>(null)
        private set
    var messages by mutableStateOf<List<AgentChatMessage>>(emptyList())
        private set
    var busy by mutableStateOf(false)
        private set
    var installStatus by mutableStateOf(AgentInstallStatus())
        private set
    var attachmentProgress by mutableStateOf<String?>(null)
        private set
    var reconnecting by mutableStateOf(false)
        private set

    private val json = Json { ignoreUnknownKeys = true }
    private var ssh: SshSession? = null
    private var channel: SshExecChannel? = null
    private var readerJob: Job? = null
    private var reconnectJob: Job? = null
    private var readBuffer = ByteArray(0)
    private var nextRequestId = 1
    private val pending = mutableMapOf<Int, CompletableDeferred<JsonObject>>()

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
        scope.launch {
            try {
                val result = request("sessions.get", buildJsonObject { put("sessionId", id) })
                currentSession = parseSession(result)
                busy = result.boolean("busy")
                messages = result.array("messages").mapNotNull(::parseMessage)
            } catch (e: Exception) {
                errorMessage = e.message
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
            try {
                val params =
                    buildJsonObject {
                        put("agent", agent)
                        if (!cwd.isNullOrBlank()) put("cwd", cwd.trim())
                        if (!model.isNullOrBlank()) put("model", model.trim())
                        put("provider", provider.toJson())
                    }
                val created = request("sessions.create", params)
                val sessionId = created.string("sessionId")
                refreshSessions()
                val selected = request("sessions.get", buildJsonObject { put("sessionId", sessionId) })
                currentSession = parseSession(selected)
                messages = selected.array("messages").mapNotNull(::parseMessage)
                busy = false
                sendPromptInternal(text, attachments, provider, onAccepted)
            } catch (e: Exception) {
                busy = false
                errorMessage = e.message
                attachmentProgress = null
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
    ) {
        val selected = currentSession ?: return
        if (busy || text.isBlank()) return
        busy = true
        try {
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
            errorMessage = null
            onAccepted()
        } catch (e: Exception) {
            busy = false
            attachmentProgress = null
            val message = e.message ?: "Prompt failed"
            errorMessage = message
            messages = messages + AgentChatMessage(role = "error", text = message, isError = true)
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

    fun deleteSession(id: String) {
        scope.launch {
            try {
                request("sessions.delete", buildJsonObject { put("sessionId", id) })
                if (currentSession?.id == id) newChat()
                refreshSessions()
            } catch (e: Exception) {
                errorMessage = e.message
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

    fun newChat() {
        currentSession = null
        messages = emptyList()
        busy = false
    }

    fun close() {
        // Mark the controller closed before tearing down the raw channel. A blocked
        // reader may wake up synchronously from close(); it must not interpret that
        // intentional EOF as a disconnect and start a new SSH connection.
        state = AgentBridgeState.IDLE
        reconnectJob?.cancel()
        reconnectJob = null
        reconnecting = false
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
        readBuffer = ByteArray(0)
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
        refreshAgents()
        refreshSessions()
        state = AgentBridgeState.READY
        reconnecting = false
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
                var lastError: Exception = IllegalStateException(reason)
                repeat(3) { attempt ->
                    delay((attempt + 1) * 1_000L)
                    try {
                        ensureSsh()
                        val probe = probeBridge()
                        if (
                            probe !is ProbeResult.Ready ||
                            probe.protocol != BRIDGE_PROTOCOL_VERSION ||
                            versionIsOlder(probe.version, MINIMUM_BRIDGE_VERSION)
                        ) {
                            throw IllegalStateException("Agent Bridge update required")
                        }
                        bridgeVersion = probe.version
                        openProtocol()
                        selectedSessionId?.let(::selectSession)
                        errorMessage = null
                        reconnectJob = null
                        return@launch
                    } catch (e: Exception) {
                        lastError = e
                        channel?.close()
                        channel = null
                        ssh?.close()
                        ssh = null
                    }
                }
                reconnectJob = null
                reconnecting = false
                fail(lastError)
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
        return try {
            withTimeout(REQUEST_TIMEOUT_MS) { deferred.await() }
        } finally {
            pending.remove(id)
        }
    }

    private fun consume(chunk: ByteArray) {
        readBuffer += chunk
        while (true) {
            val newline = readBuffer.indexOf(0x0A)
            if (newline < 0) return
            val line = readBuffer.copyOfRange(0, newline).decodeToString().trimEnd('\r')
            readBuffer = readBuffer.copyOfRange(newline + 1, readBuffer.size)
            if (line.isNotBlank()) handleLine(line)
        }
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
            "busy" -> {
                if (value.stringOrNull("sessionId") == currentSession?.id) {
                    busy = value.boolean("busy")
                }
            }
            "event" -> handleEvent(value)
            "session_deleted" -> scope.launch { refreshSessions() }
        }
    }

    private fun handleEvent(envelope: JsonObject) {
        val event = envelope["event"] as? JsonObject ?: return
        val sessionId = envelope.stringOrNull("sessionId")
        val type = event.stringOrNull("type") ?: return
        if (sessionId != null && sessionId != currentSession?.id) return
        when (type) {
            "delta" -> appendStream("assistant", event.string("text"), event)
            "thinking_start" -> startThinking(event)
            "thinking_delta" -> appendStream("thinking", event.string("text"), event)
            "thinking" -> replaceOrAppend("thinking", event.string("text"), event)
            "assistant_message" -> replaceOrAppend("assistant", event.string("text"), event)
            "tool_start" -> startTool(event)
            "tool_end" -> finishTool(event)
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
            "cancelled" -> busy = false
            "settled" -> {
                busy = false
                val completedAt = Clock.System.now().toEpochMilliseconds()
                messages =
                    messages.map {
                        if (it.running) it.copy(running = false, completedAt = completedAt) else it
                    }
                scope.launch { refreshSessions() }
            }
            "install_output" -> {
                installLog += event.string("text")
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
                installLog += if (installLog.isBlank()) message else "\n$message"
                installStatus = AgentInstallStatus(event.stringOrNull("agent"), AgentInstallPhase.FAILED, message)
            }
        }
    }

    private fun appendStream(
        role: String,
        text: String,
        event: JsonObject,
    ) {
        if (text.isEmpty()) return
        val activityId = event.stringOrNull("activityId")
        val index = messages.indexOfLast { it.role == role && it.running && it.activityId == activityId }
        messages =
            if (index >= 0) {
                messages.toMutableList().also { items ->
                    items[index] = items[index].copy(text = items[index].text + text)
                }
            } else {
                messages +
                    AgentChatMessage(
                        role = role,
                        text = text,
                        id = activityId.orEmpty(),
                        turnId = event.string("turnId"),
                        activityId = activityId,
                        running = true,
                        createdAt = event.long("ts"),
                    )
            }
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

    private fun startThinking(event: JsonObject) {
        val activityId = event.stringOrNull("activityId")
        if (messages.any { it.role == "thinking" && it.activityId == activityId }) return
        messages =
            messages +
            AgentChatMessage(
                role = "thinking",
                text = "",
                id = activityId.orEmpty(),
                turnId = event.string("turnId"),
                activityId = activityId,
                running = true,
                createdAt = event.long("ts"),
            )
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
        )

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
        )
    }

    private suspend fun uploadAttachments(
        session: AgentBridgeSessionInfo,
        attachments: List<AgentPendingAttachment>,
    ): List<AgentAttachment> {
        if (attachments.isEmpty()) return emptyList()
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
        return try {
            attachments.mapIndexed { index, pending ->
                val safeName = sanitizeFileName(pending.name)
                val storedName = "${session.id.take(8)}-${index + 1}-$safeName"
                val relativePath = "$relativeDirectory/$storedName"
                attachmentProgress = "${index + 1} / ${attachments.size} · ${pending.name}"
                withContext(ioDispatcher()) {
                    sftp.upload(
                        remotePath = joinRemotePath(session.cwd, relativePath),
                        totalSize = pending.size,
                        onProgress = { sent, total ->
                            val percent = if (total > 0) (sent * 100 / total).toInt() else 0
                            attachmentProgress = "${index + 1} / ${attachments.size} · $percent%"
                        },
                        nextChunk = pending.readChunk,
                    )
                }
                AgentAttachment(pending.name, relativePath, pending.size)
            }
        } finally {
            withContext(ioDispatcher()) { sftp.close() }
            attachmentProgress = null
        }
    }

    private fun fail(error: Exception) {
        TermLog.e("agent") { "bridge ${host.name}: ${error.message}" }
        errorMessage = error.message ?: "Agent Bridge failed"
        reconnecting = false
        state = AgentBridgeState.ERROR
    }

    private fun failPending(error: Exception) {
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
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
    }

private fun joinRemotePath(
    base: String,
    child: String,
): String = if (base == "/") "/${child.trimStart('/')}" else "${base.trimEnd('/')}/${child.trimStart('/')}"

private fun sanitizeFileName(name: String): String {
    val clean = name.map { character -> if (character.isLetterOrDigit() || character in "._-") character else '_' }.joinToString("")
    return clean.trim('.').ifBlank { "attachment" }.take(120)
}

package dev.termish.agent

import com.russhwolf.settings.MapSettings
import dev.termish.data.Host
import dev.termish.data.HostRepository
import dev.termish.ssh.AuthPrompt
import dev.termish.ssh.HostKeyInfo
import dev.termish.ssh.SshCallbacks
import dev.termish.ssh.SshExecChannel
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Test

/** Exercises the real controller with independently scheduled NDJSON replies and events. */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentBridgeControllerTest {
    private val scope = TestScope()
    private val wire = FakeChannel()
    private val controller =
        AgentBridgeController(
            Host("test", "test", "127.0.0.1"),
            HostRepository(MapSettings()),
            object : SshCallbacks {
                override suspend fun onOutput(data: ByteArray) {}

                override suspend fun onStderr(data: ByteArray) {}

                override fun onExitStatus(status: Int) {}

                override fun onClosed(reason: String?) {}

                override suspend fun onPrompt(prompt: AuthPrompt): List<String>? = null

                override fun verifyHostKey(hostKey: HostKeyInfo) = false
            },
            scope,
        ).also {
            it.javaClass
                .getDeclaredField("channel")
                .apply { isAccessible = true }
                .set(it, wire)
        }

    @After
    fun close() {
        controller.close()
        scope.runCurrent()
        scope.cancel()
    }

    @Test
    fun acceptedPromptDoesNotAppearInAnotherConversation() {
        select("A")
        var accepted = 0
        controller.sendPrompt("Only A", onAccepted = { accepted++ })
        scope.runCurrent()
        val readiness = wire.take("agents.check")
        select("B")
        reply(readiness, "{}")
        val send = wire.take("prompt.send")
        assertEquals("A", send["params"]!!.jsonObject["sessionId"]!!.jsonPrimitive.content)
        reply(send, """{"accepted":true,"turnId":"turn-A"}""")
        assertEquals("B", controller.currentSession?.id)
        assertTrue(controller.messages.isEmpty())
        assertFalse(controller.busy)
        assertTrue(controller.hasActiveWork)
        assertEquals(1, accepted)
        reply(wire.take("sessions.list"), """{"sessions":[${snapshot("A", busy = true)},${snapshot("B") }]}""")
        select("A", busy = true)
        assertTrue(controller.busy)
    }

    @Test
    fun failedPromptDoesNotSetAnotherConversationsError() {
        select("A")
        controller.sendPrompt("Only A")
        scope.runCurrent()
        val readiness = wire.take("agents.check")
        select("B")
        reject(readiness)
        assertNull(controller.errorMessage)
        assertTrue(controller.messages.isEmpty())
        assertFalse(controller.busy)
    }

    @Test
    fun returningToOriginalSessionDoesNotDuplicateAnAlreadyLoadedPrompt() {
        select("A")
        controller.sendPrompt("Only A")
        scope.runCurrent()
        reply(wire.take("agents.check"), "{}")
        val send = wire.take("prompt.send")
        select("B")
        controller.selectSession("A")
        scope.runCurrent()
        val loaded =
            snapshot("A", busy = true).replace(
                "\"messages\":[]",
                """"messages":[{"role":"user","text":"Only A","messageId":"server-id","turnId":"turn-A"}]""",
            )
        reply(wire.take("sessions.get"), loaded)
        reply(send, """{"accepted":true,"turnId":"turn-A"}""")
        assertEquals(listOf("Only A"), controller.messages.map { it.text })
        assertEquals("server-id", controller.messages.single().id)
    }

    @Test
    fun firstPromptDoesNotNavigateBackAfterSelectingAnotherConversation() {
        startConversation()
        val create = wire.take("sessions.create")
        select("B")
        reply(create, snapshot("A"))
        reply(wire.take("agents.check"), "{}")
        reply(wire.take("prompt.send"), """{"accepted":true,"turnId":"turn-A"}""")
        reply(wire.take("sessions.get"), snapshot("A", busy = true))
        assertEquals("B", controller.currentSession?.id)
        assertTrue(controller.messages.isEmpty())
        assertFalse(controller.busy)
        assertTrue(controller.hasActiveWork)
    }

    @Test
    fun stoppingReadinessNeverSubmitsPrompt() {
        select("A")
        controller.sendPrompt("Stop me")
        scope.runCurrent()
        val readiness = wire.take("agents.check")
        controller.abort()
        scope.runCurrent()
        reply(readiness, "{}")
        assertFalse(wire.contains("prompt.send"))
        assertFalse(wire.contains("prompt.abort"))
        assertFalse(controller.busy)
        assertFalse(controller.hasActiveWork)
        assertNull(controller.errorMessage)
    }

    @Test
    fun stoppingBeforeCoroutineStartsReleasesBusyState() {
        select("A")
        controller.sendPrompt("Stop immediately")
        controller.abort()
        scope.runCurrent()
        assertFalse(controller.busy)
        assertFalse(controller.hasActiveWork)
        assertFalse(wire.contains("agents.check"))
    }

    @Test
    fun readinessTimeoutReportsFailureAndReleasesBusyState() {
        select("A")
        controller.sendPrompt("Readiness times out")
        scope.runCurrent()
        scope.advanceTimeBy(agentRequestTimeoutMillis("agents.check") + 1)
        scope.runCurrent()
        assertFalse(controller.busy)
        assertFalse(controller.hasActiveWork)
        assertTrue(controller.errorMessage.orEmpty().isNotEmpty())
        assertFalse(wire.contains("prompt.send"))
    }

    @Test
    fun stoppingFirstPromptWaitsForIdAndDeletesOnlyItsEmptySession() {
        startConversation()
        val create = wire.take("sessions.create")
        controller.abort()
        scope.runCurrent()
        reply(create, snapshot("A"))
        val delete = wire.take("sessions.delete")
        assertEquals("A", delete["params"]!!.jsonObject["sessionId"]!!.jsonPrimitive.content)
        reply(delete, "{}")
        assertFalse(wire.contains("agents.check"))
        assertFalse(wire.contains("prompt.send"))
        assertNull(controller.currentSession)
        assertFalse(controller.busy)
        assertFalse(controller.hasActiveWork)
    }

    @Test
    fun stoppingAnAlreadySubmittedPromptAbortsItsOriginalSession() {
        select("A")
        controller.sendPrompt("Stop submitted task")
        scope.runCurrent()
        reply(wire.take("agents.check"), "{}")
        val send = wire.take("prompt.send")
        controller.abort()
        scope.runCurrent()
        val abort = wire.take("prompt.abort")
        select("B")
        reply(send, """{"accepted":true,"turnId":"turn-A"}""")
        event("A", """{"type":"cancelled"}""")
        reply(abort, "{}")
        assertEquals("A", abort["params"]!!.jsonObject["sessionId"]!!.jsonPrimitive.content)
        assertFalse(controller.busy)
        assertFalse(controller.hasActiveWork)
        assertTrue(controller.messages.isEmpty())
    }

    @Test
    fun backgroundApprovalsAndCompletionNotifyOnceAndKeepHostAlive() {
        select("A", busy = true)
        select("B")
        val approvals = mutableListOf<String>()
        val settled = mutableListOf<String?>()
        controller.onApprovalRequested = { approvals += it.id }
        controller.onTaskSettled = { settled += it?.id }
        event("A", approval())
        event("A", approval())
        assertEquals(listOf("p1"), approvals)
        assertTrue(controller.pendingApprovals.isEmpty())
        assertTrue(controller.hasActiveWork)
        controller.newChat()
        assertFalse(controller.busy)
        assertTrue(controller.hasActiveWork)
        event("A", """{"type":"settled"}""")
        event("A", """{"type":"settled"}""")
        assertEquals(listOf<String?>("A"), settled)
        assertFalse(controller.hasActiveWork)
    }

    @Test
    fun abandoningSessionLoadPreservesItsQueuedApproval() {
        select("A", busy = true)
        select("B")
        val approvals = mutableListOf<String>()
        controller.onApprovalRequested = { approvals += it.id }
        controller.selectSession("A")
        scope.runCurrent()
        val oldLoad = wire.take("sessions.get")
        event("A", approval())
        controller.newChat()
        assertEquals(listOf("p1"), approvals)
        assertTrue(controller.hasActiveWork)
        reply(oldLoad, snapshot("A", busy = true))
        assertNull(controller.currentSession)
        assertTrue(controller.pendingApprovals.isEmpty())
    }

    @Test
    fun backgroundReplayRecoversApprovalWithoutReplacingVisibleMessages() {
        select("A", busy = true, epoch = true)
        select("B")
        val approvals = mutableListOf<String>()
        controller.onApprovalRequested = { approvals += it.id }
        feed("""{"type":"event","sessionId":"A","eventEpoch":"epoch","eventSeq":3,"event":${approval()}}""")
        scope.runCurrent()
        val replay = wire.take("events.replay")
        reply(replay, """{"reset":true,"eventEpoch":"epoch","eventCursor":3,"events":[]}""")
        reply(wire.take("sessions.get"), snapshot("A", busy = true, approvals = approval(), epoch = true, cursor = 3))
        assertEquals(listOf("p1"), approvals)
        assertEquals("B", controller.currentSession?.id)
        assertTrue(controller.messages.isEmpty())
        select("A", busy = true, approvals = approval(), epoch = true, cursor = 3)
        assertEquals(listOf("p1"), controller.pendingApprovals.map { it.id })
        assertEquals(listOf("p1"), approvals)
    }

    @Test
    fun staleSessionListCannotOverwriteNewerCompletion() {
        select("A", busy = true)
        event("A", """{"type":"settled"}""")
        val list = wire.take("sessions.list")
        feed("""{"type":"busy","sessionId":"A","busy":true}""")
        scope.runCurrent()
        reply(list, """{"sessions":[${snapshot("A")}]}""")
        assertTrue(controller.busy)
        assertTrue(controller.sessions.single().busy)
        assertTrue(controller.hasActiveWork)
    }

    @Test
    fun failedInstallationCanBeRetriedAndAgentsHaveIndependentState() {
        controller.installAgent("codex")
        controller.installAgent("pi")
        scope.runCurrent()
        reply(wire.take("agents.install"), "{}")
        reply(wire.take("agents.install"), "{}")
        event(null, """{"type":"install_error","agent":"codex","message":"npm unavailable"}""")
        assertFalse(controller.isAgentInstalling("codex"))
        assertTrue(controller.isAgentInstalling("pi"))
        controller.installAgent("codex")
        scope.runCurrent()
        assertTrue(controller.isAgentInstalling("codex"))
        assertTrue(wire.contains("agents.install"))
        assertTrue(controller.hasActiveWork)
    }

    @Test
    fun disconnectedInstallFailsInsteadOfStayingDisabled() {
        controller.close()
        controller.installAgent("codex")
        scope.runCurrent()
        assertEquals(AgentInstallPhase.FAILED, controller.installStatus.phase)
        assertFalse(controller.isAgentInstalling("codex"))
        assertFalse(controller.hasActiveWork)
    }

    @Test
    fun agentRefreshRecoversRunningInstallationAndCannotOverwriteNewInstall() {
        event(null, """{"type":"install_complete","agent":"codex"}""")
        val refresh = wire.take("agents.list")
        controller.installAgent("codex")
        scope.runCurrent()
        reply(refresh, """{"agents":[],"installing":["pi"]}""")
        assertTrue(controller.isAgentInstalling("codex"))
        assertTrue(controller.isAgentInstalling("pi"))
        controller.close()
        assertFalse(controller.isAgentInstalling("codex"))
        assertFalse(controller.isAgentInstalling("pi"))
    }

    private fun startConversation() {
        controller.startConversation("codex", "/tmp", null, null, "New task", emptyList())
        scope.runCurrent()
    }

    private fun select(
        id: String,
        busy: Boolean = false,
        approvals: String = "",
        epoch: Boolean = false,
        cursor: Int = 0,
    ) {
        controller.selectSession(id)
        scope.runCurrent()
        reply(wire.take("sessions.get"), snapshot(id, busy, approvals, epoch, cursor))
    }

    private fun snapshot(
        id: String,
        busy: Boolean = false,
        approvals: String = "",
        epoch: Boolean = false,
        cursor: Int = 0,
    ) = """{"sessionId":"$id","agent":"codex","cwd":"/tmp/$id","title":"$id","busy":$busy,"waitingApproval":${approvals.isNotEmpty()},"messages":[],"approvals":[$approvals],"eventEpoch":"${if (epoch) "epoch" else ""}","eventCursor":$cursor}"""

    private fun approval() = """{"type":"approval_request","approvalId":"p1","sessionId":"A","agent":"codex","kind":"confirm","title":"Approval","options":["allow_once","deny"]}"""

    private fun event(
        sessionId: String?,
        event: String,
    ) {
        val session = sessionId?.let { "\"sessionId\":\"$it\"," }.orEmpty()
        feed("""{"type":"event",$session"event":$event}""")
        scope.runCurrent()
    }

    private fun reply(
        request: JsonObject,
        result: String,
    ) {
        feed("""{"id":${request["id"]},"result":$result}""")
        scope.runCurrent()
    }

    private fun reject(request: JsonObject) {
        feed("""{"id":${request["id"]},"error":{"message":"Readiness failed"}}""")
        scope.runCurrent()
    }

    private fun feed(line: String) {
        controller.javaClass
            .getDeclaredMethod("handleLine", String::class.java)
            .apply { isAccessible = true }
            .invoke(controller, line)
    }

    private class FakeChannel : SshExecChannel {
        private val requests = mutableListOf<JsonObject>()

        override fun read(): ByteArray? = null

        override fun readErr(): ByteArray? = null

        override fun write(data: ByteArray) {
            requests += Json.parseToJsonElement(data.decodeToString()).jsonObject
        }

        override fun close() {}

        fun contains(method: String) = requests.any { it["method"]!!.jsonPrimitive.content == method }

        fun take(method: String): JsonObject {
            val index = requests.indexOfFirst { it["method"]!!.jsonPrimitive.content == method }
            check(index >= 0) { "Missing $method; pending requests: $requests" }
            return requests.removeAt(index)
        }
    }
}

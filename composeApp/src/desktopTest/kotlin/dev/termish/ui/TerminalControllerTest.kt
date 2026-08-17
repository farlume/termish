package dev.termish.ui

import com.russhwolf.settings.PropertiesSettings
import dev.termish.data.Host
import dev.termish.data.HostRepository
import dev.termish.ssh.CommandResult
import dev.termish.ssh.HostKeyInfo
import dev.termish.ssh.SessionInfo
import dev.termish.ssh.SshCallbacks
import dev.termish.ssh.SshConnection
import dev.termish.ssh.SshSession
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout

/**
 * TerminalController 状态机与输出管线（fake SshSession + 可控主调度器）：
 * 连接状态转换、TOFU 指纹保存、启动命令、意外断线重连、close/destroy 语义、
 * CancellationException 不误报失败、输出经主线程消费进 buffer。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TerminalControllerTest {

    private val scheduler = TestCoroutineScheduler()
    private val mainDispatcher = StandardTestDispatcher(scheduler)

    @BeforeTest
    fun setupMain() {
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterTest
    fun tearDownMain() {
        Dispatchers.resetMain()
    }

    private fun repo() = HostRepository(PropertiesSettings(Properties()))

    private fun host(startup: String = "") =
        Host(id = "h1", name = "dev", hostname = "example.com", username = "root", startupCommand = startup)

    private class FakeSsh(
        var connectError: Throwable? = null,
    ) : SshSession {
        lateinit var callbacks: SshCallbacks
        val sent = mutableListOf<ByteArray>()
        var closed = false
        var resized = 0

        override fun connectAndStart(columns: Int, rows: Int): SessionInfo {
            connectError?.let { throw it }
            return SessionInfo(
                serverVersion = "SSH-2.0-fake",
                hostKey = HostKeyInfo("ssh-ed25519", "SHA256:test", "md5"),
                kexAlgorithm = "curve25519-sha256",
            )
        }

        override fun resize(columns: Int, rows: Int, widthPx: Int, heightPx: Int) {
            resized++
        }

        override fun sendData(data: ByteArray) {
            sent += data
        }

        override fun connectAndRun(command: String, timeoutMs: Long): CommandResult =
            throw UnsupportedOperationException()

        override fun probeSystem(): String? = null

        override fun close() {
            closed = true
        }

        override fun isActive(): Boolean = !closed
    }

    private fun controller(
        fake: FakeSsh,
        repo: HostRepository = repo(),
        autoReconnect: Boolean = true,
        startup: String = "",
    ): Triple<TerminalController, FakeSsh, HostRepository> {
        val r = repo
        val c = TerminalController(host(startup), "pw", null, r, autoReconnect) { _, cb ->
            fake.callbacks = cb
            fake
        }
        return Triple(c, fake, r)
    }

    private fun awaitStatus(c: TerminalController, expected: ConnStatus) = runBlocking {
        withTimeout(5_000) {
            while (c.status != expected) delay(10)
        }
    }

    private fun TerminalController.bufferText(): String = buildString {
        val b = buffer
        for (r in 0 until b.rows) {
            val line = b.absLine(b.totalLines() - b.rows + r)
            for (col in 0 until b.cols) append(line.cells[col].codePoint.toChar())
            append('\n')
        }
    }

    @Test
    fun connectSuccessSetsConnectedAndRecordsFingerprint() {
        val (c, fake, r) = controller(FakeSsh())
        r.upsertHost(host())
        c.connect(80, 24)
        awaitStatus(c, ConnStatus.CONNECTED)

        assertEquals("SHA256:test", r.getHost("h1")!!.knownHostFingerprint)
        assertTrue(!fake.closed)
        c.destroy()
    }

    @Test
    fun connectSendsStartupCommand() {
        val (c, fake, _) = controller(FakeSsh(), startup = "tmux new -A -s main")
        c.connect(80, 24)
        awaitStatus(c, ConnStatus.CONNECTED)
        // CONNECTED 置位后启动命令在同一协程稍后发出：轮询等待，避免竞态
        runBlocking {
            withTimeout(5_000) {
                while (fake.sent.none { it.decodeToString().trim() == "tmux new -A -s main" }) delay(10)
            }
        }

        assertTrue(fake.sent.any { it.decodeToString().trim() == "tmux new -A -s main" })
        c.destroy()
    }

    @Test
    fun connectFailureSetsError() {
        val (c, _, _) = controller(FakeSsh(connectError = IllegalStateException("boom")))
        c.connect(80, 24)
        awaitStatus(c, ConnStatus.ERROR)

        assertTrue(c.errorMessage!!.contains("boom"))
        c.destroy()
    }

    @Test
    fun cancellationIsNotReportedAsError() {
        val (c, _, _) = controller(FakeSsh(connectError = CancellationException("cancelled")))
        c.connect(80, 24)
        runBlocking { delay(200) } // 让 IO 上的连接协程跑完

        assertNotEquals(ConnStatus.ERROR, c.status)
        c.destroy()
    }

    @Test
    fun outputReachesEmulatorViaMainConsumer() {
        val (c, fake, _) = controller(FakeSsh())
        c.connect(80, 24)
        awaitStatus(c, ConnStatus.CONNECTED)

        runBlocking { fake.callbacks.onOutput("hello\n".encodeToByteArray()) }
        scheduler.advanceUntilIdle()

        val text = c.bufferText()
        assertTrue(text.startsWith("hello"), "buffer 应包含输出，实际: $text")
        c.destroy()
    }

    @Test
    fun unexpectedCloseInitiatesReconnect() {
        val (c, fake, _) = controller(FakeSsh())
        c.connect(80, 24)
        awaitStatus(c, ConnStatus.CONNECTED)

        fake.callbacks.onClosed(null)

        assertEquals(ConnStatus.CONNECTING, c.status)
        assertEquals(1, c.reconnectCount)
        c.destroy()
    }

    @Test
    fun noAutoReconnectLeavesClosed() {
        val (c, fake, _) = controller(FakeSsh(), autoReconnect = false)
        c.connect(80, 24)
        awaitStatus(c, ConnStatus.CONNECTED)

        fake.callbacks.onClosed("bye")

        assertEquals(ConnStatus.CLOSED, c.status)
        assertEquals("bye", c.errorMessage)
        c.destroy()
    }

    @Test
    fun closeStopsReconnect() {
        val (c, fake, _) = controller(FakeSsh())
        c.connect(80, 24)
        awaitStatus(c, ConnStatus.CONNECTED)

        c.close()
        assertEquals(ConnStatus.CLOSED, c.status)

        fake.callbacks.onClosed(null)
        assertEquals(ConnStatus.CLOSED, c.status)
    }

    @Test
    fun destroyDropsFurtherOutput() {
        val (c, fake, _) = controller(FakeSsh())
        c.connect(80, 24)
        awaitStatus(c, ConnStatus.CONNECTED)
        val frameBefore = c.frame

        c.destroy()
        runBlocking { fake.callbacks.onOutput("late".encodeToByteArray()) }
        scheduler.advanceUntilIdle()

        assertEquals(frameBefore, c.frame)
    }
}

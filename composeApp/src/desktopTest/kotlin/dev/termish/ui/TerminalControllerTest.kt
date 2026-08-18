package dev.termish.ui

import com.russhwolf.settings.PropertiesSettings
import dev.termish.data.ConnectionMode
import dev.termish.data.Host
import dev.termish.data.HostRepository
import dev.termish.ssh.CommandResult
import dev.termish.ssh.HostKeyInfo
import dev.termish.ssh.SessionInfo
import dev.termish.ssh.SshCallbacks
import dev.termish.ssh.SshConnection
import dev.termish.ssh.SshExecChannel
import dev.termish.ssh.SshSession
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
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

    private fun herdrHost() =
        host().copy(connectionMode = ConnectionMode.HERDR)

    /** 最小合法 herdr 快照（HerdrSessionSnapshot 字段全有默认值）。 */
    private val snapshotJson = """{"id":"x","result":{"snapshot":{}}}"""

    private class FakeExec(
        val onWrite: (ByteArray) -> Unit = {},
        /** true = read 立即返回 null（模拟脚本/程序已退出 EOF）；默认返回空块保持通道活跃。 */
        val eof: Boolean = false,
    ) : SshExecChannel {
        var closed = false

        override fun read(): ByteArray? = if (closed || eof) null else ByteArray(0)

        override fun write(data: ByteArray) = onWrite(data)

        override fun close() {
            closed = true
        }
    }

    private class FakeSsh(
        var connectError: Throwable? = null,
        var commandHandler: (String) -> String? = { null },
        var execFactory: ((String) -> SshExecChannel?)? = null,
    ) : SshSession {
        lateinit var callbacks: SshCallbacks
        val sent = mutableListOf<ByteArray>()
        val execCommands = mutableListOf<String>()
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

        override fun runCommand(command: String, timeoutMs: Long): String? = commandHandler(command)

        override fun startExec(command: String, columns: Int, rows: Int): SshExecChannel? {
            execCommands += command
            return execFactory?.invoke(command)
        }

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

    private fun herdrController(
        fake: FakeSsh,
        repo: HostRepository = repo(),
    ): Pair<TerminalController, FakeSsh> {
        val c = TerminalController(herdrHost(), "pw", null, repo, false) { _, cb ->
            fake.callbacks = cb
            fake
        }
        return c to fake
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

    // ---------- HERDR 连接状态机 ----------

    @Test
    fun herdrProbeFailureGuidesInstall() {
        // 全部候选 runCommand 返回 null → 远端无 herdr：保留 SSH 连接进入「待安装」
        // （banner 引导安装），不报错、不降级、不重连
        val (c, f) = herdrController(FakeSsh())
        c.connect(80, 24)
        awaitStatus(c, ConnStatus.CONNECTED)

        assertTrue(c.herdrNeedsInstall, "应进入引导安装状态")
        assertTrue(!f.closed, "SSH 引导通道应保留（安装脚本在它上面执行）")
        c.destroy()
    }

    @Test
    fun installHerdrReProbesAndContinues() {
        // 首次探测无 herdr → 引导安装；安装脚本执行后重新探测成功 → 继续 HERDR
        // 连接（mosh 引导失败 → 降级 exec+pty 跑 herdr）
        var installed = false
        val fake = FakeSsh(
            commandHandler = { cmd ->
                when {
                    cmd.contains("api snapshot") -> if (installed) snapshotJson else null
                    else -> "no mosh-server here"
                }
            },
            execFactory = { cmd ->
                // 安装脚本经 startExec 流式执行（不经过 commandHandler）：这里模拟安装成功
                if (cmd.contains("install.sh")) installed = true
                FakeExec(eof = true)
            },
        )
        val (c, f) = herdrController(fake)
        c.connect(80, 24)
        awaitStatus(c, ConnStatus.CONNECTED)
        assertTrue(c.herdrNeedsInstall)

        c.installHerdr()
        // 安装成功后继续连接：降级 exec 跑 herdr（execCommands 末尾为 "herdr"）
        runBlocking { withTimeout(5_000) { while (f.execCommands.none { it == "herdr" }) delay(10) } }

        assertTrue(!c.herdrNeedsInstall, "安装成功应退出待安装状态")
        assertTrue(!c.herdrInstalling)
        assertTrue(f.execCommands.any { it.contains("install.sh") }, "应执行安装脚本")
        assertEquals("herdr", f.execCommands.last())
        c.destroy()
    }

    @Test
    fun parseInstallPathExtractsFromLog() {
        // install.sh 打印 "installed herdr to /path/herdr"（含颜色码前缀与多行日志）
        assertEquals("/home/user/.local/bin/herdr", parseInstallPath("  > installed herdr to /home/user/.local/bin/herdr\n"))
        assertEquals("/opt/herdr", parseInstallPath("downloading...\ninstalled herdr to /opt/herdr\n  > ready"))
        assertNull(parseInstallPath("download failed"))
        assertNull(parseInstallPath(""))
    }

    @Test
    fun herdrBootstrapFailureDegradesToExecAndConnected() {
        // snapshot 有效但 mosh 引导无 MOSH CONNECT → 降级 exec+pty；状态必须置
        // CONNECTED（回归：此前状态停在 CONNECTING，banner 常驻「连接中…」）
        val fake = FakeSsh(
            commandHandler = { cmd ->
                when {
                    cmd.contains("api snapshot") -> snapshotJson
                    else -> "no mosh-server here"
                }
            },
            execFactory = { FakeExec() },
        )
        val (c, f) = herdrController(fake)
        c.connect(80, 24)
        awaitStatus(c, ConnStatus.CONNECTED)

        assertEquals(listOf("herdr"), f.execCommands)
        c.destroy()
    }

    @Test
    fun herdrExecUnavailableFallsBackToShell() {
        // startExec 不可用（iOS 现状）→ shell 命令路径，同样置 CONNECTED
        val fake = FakeSsh(
            commandHandler = { cmd ->
                if (cmd.contains("api snapshot")) snapshotJson else null
            },
            execFactory = { null },
        )
        val (c, f) = herdrController(fake)
        c.connect(80, 24)
        awaitStatus(c, ConnStatus.CONNECTED)

        assertTrue(f.sent.any { it.decodeToString() == "herdr\n" }, "应经 shell 发送 herdr 命令")
        c.destroy()
    }

    @Test
    fun herdrProbeUsesFullPathCandidates() {
        // 裸 herdr 不在 PATH、全路径命中：探测记录候选顺序，命中路径贯穿 exec
        val probed = mutableListOf<String>()
        val fake = FakeSsh(
            commandHandler = { cmd ->
                if (cmd.contains("api snapshot")) {
                    probed += cmd
                    if (cmd.startsWith("/usr/local/bin/herdr")) snapshotJson else null
                } else {
                    null
                }
            },
            execFactory = { FakeExec() },
        )
        val (c, f) = herdrController(fake)
        c.connect(80, 24)
        awaitStatus(c, ConnStatus.CONNECTED)

        // 探测按候选顺序：herdr → \$HOME/.local/bin/herdr → /usr/local/bin/herdr（命中）
        assertEquals(3, probed.size)
        assertTrue(probed[0].startsWith("herdr "))
        assertTrue(probed[2].startsWith("/usr/local/bin/herdr "))
        // 命中路径贯穿降级 exec（非裸 herdr）
        assertEquals(listOf("/usr/local/bin/herdr"), f.execCommands)
        c.destroy()
    }
}

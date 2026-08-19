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

    private fun moshController(
        fake: FakeSsh,
        repo: HostRepository = repo(),
    ): Pair<TerminalController, FakeSsh> {
        val c = TerminalController(host().copy(connectionMode = ConnectionMode.MOSH), "pw", null, repo, false) { _, cb ->
            fake.callbacks = cb
            fake
        }
        return c to fake
    }

    private fun awaitStatus(c: TerminalController, expected: ConnStatus) = runBlocking {
        withTimeout(5_000) {
            while (c.status != expected) {
                delay(10)
                // 泵主线程消费者：fake exec 的读循环热转产空块会把 256 容量
                // 输出队列灌满，不排干则读循环卡在背压上、后续状态永远等不到
                scheduler.advanceUntilIdle()
            }
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
        // 连接（mosh 缺 → mosh 引导卡片；降级 SSH → exec+pty 跑 herdr）
        var installed = false
        val fake = FakeSsh(
            commandHandler = { cmd ->
                when {
                    cmd.contains("api snapshot") -> if (installed) snapshotJson else null
                    else -> "mosh: command not found"
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
        // 安装成功后继续连接：mosh-server 仍缺 → mosh 引导卡片（不再静默降级）；
        // 用户点「降级 SSH」后 exec 跑 herdr（execCommands 末尾为转义后的 'herdr'）
        val quotedHerdr = shSingleQuote("herdr")
        runBlocking { withTimeout(5_000) { while (!(!c.herdrInstalling && !c.herdrNeedsInstall && c.moshNeedsInstall)) delay(10) } }
        c.degradeMoshToSsh()
        runBlocking { withTimeout(5_000) { while (f.execCommands.none { it == quotedHerdr }) delay(10) } }

        assertTrue(!c.herdrNeedsInstall, "安装成功应退出待安装状态")
        assertTrue(!c.moshNeedsInstall, "降级后应退出 mosh 引导卡片")
        assertTrue(!c.herdrInstalling)
        assertTrue(f.execCommands.any { it.contains("install.sh") }, "应执行安装脚本")
        assertEquals(quotedHerdr, f.execCommands.last())
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
    fun shSingleQuoteEscapesForSudoPipe() {
        // 普通密码：原样包单引号
        assertEquals("'abc'", shSingleQuote("abc"))
        // 含单引号：拆段转义 `'` → `'\''`，shell 拼接后还原原密码
        assertEquals("'a'\\''b'", shSingleQuote("a'b"))
        assertEquals("'a'\\''b'\\''c'", shSingleQuote("a'b'c"))
        // 含 shell 敏感字符：单引号内全部安全，无需额外转义
        assertEquals("'a\$b c\\d\"e'", shSingleQuote("a\$b c\\d\"e"))
        // 空密码
        assertEquals("''", shSingleQuote(""))
    }

    @Test
    fun herdrMoshMissingShowsInstallGuide() {
        // HERDR 模式：herdr 在但 mosh-server 未装 → mosh 引导安装卡片（非静默
        // 降级——装上 mosh 才有漫游能力）；状态必须置 CONNECTED（回归：此前
        // 状态停在 CONNECTING，banner 常驻「连接中…」）
        val fake = FakeSsh(
            commandHandler = { cmd ->
                when {
                    cmd.contains("api snapshot") -> snapshotJson
                    else -> "mosh: command not found"
                }
            },
            execFactory = { FakeExec() },
        )
        val (c, f) = herdrController(fake)
        c.connect(80, 24)
        awaitStatus(c, ConnStatus.CONNECTED)

        assertTrue(c.moshNeedsInstall, "应进入 mosh 引导安装状态")
        assertTrue(f.execCommands.isEmpty(), "未装 mosh-server 不应静默降级跑 herdr exec")
        assertTrue(!f.closed, "SSH 引导通道应保留（安装在其上执行）")
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
        // 已降级条目直走 exec 路径：startExec 不可用（iOS 现状）→ shell 命令兑底
        c.moshDegradedToSsh = true
        c.connect(80, 24)
        awaitStatus(c, ConnStatus.CONNECTED)

        assertTrue(f.sent.any { it.decodeToString() == "herdr\n" }, "应经 shell 发送 herdr 命令")
        c.destroy()
    }

    /** 可控死亡的 exec 通道：dead=true 后 read 返回 null（EOF，模拟引擎吞异常）；
     *  failRead=true 后 read 抛异常（模拟引擎直接报错）。 */
    private class KillableExec : SshExecChannel {
        @Volatile var dead = false
        @Volatile var failRead = false
        var closed = false

        override fun read(): ByteArray? = when {
            closed -> null
            failRead -> throw java.io.IOException("connection reset")
            dead -> null
            else -> ByteArray(0)
        }

        override fun write(data: ByteArray) {}

        override fun close() {
            closed = true
        }
    }

    @Test
    fun herdrExecConnectionLossAutoReconnects() {
        // 降级 exec 跑 herdr 时连接死亡（exec EOF 且底层 session 已 inactive）：
        // 按意外断开处理（自动重连），而非 c.close() 直接置灰不重连
        //（回归：切到其他 app 回来 tab 变灰、需手动重连）
        var bootstrapCount = 0
        val created = mutableListOf<KillableExec>()
        val fake = FakeSsh(
            commandHandler = { cmd ->
                when {
                    cmd.contains("mosh-server new") -> { bootstrapCount++; null }
                    cmd.contains("api snapshot") -> snapshotJson
                    else -> null
                }
            },
            execFactory = { KillableExec().also(created::add) },
        )
        val c = TerminalController(herdrHost(), "pw", null, repo(), true) { _, cb ->
            fake.callbacks = cb
            fake
        }
        // UDP 不通降级过的会话条目：连接直走 herdr exec（跳过 mosh 引导）
        c.moshDegradedToSsh = true
        c.connect(80, 24)
        awaitStatus(c, ConnStatus.CONNECTED)
        assertEquals(1, created.size, "应直走 exec 跑 herdr")

        // 模拟断链：底层会话死亡（isActive=false）+ exec 通道 EOF
        fake.closed = true
        created[0].dead = true

        // 旧实现：无条件 c.close() → CLOSED；新实现：宽限判定后按意外断开重连
        awaitStatus(c, ConnStatus.CONNECTING)
        assertEquals(1, c.reconnectCount, "应排程第 1 次自动重连")
        // 重连退避（2s）后重建：再次降级 exec 跑 herdr → 回到 CONNECTED
        awaitStatus(c, ConnStatus.CONNECTED)
        assertTrue(created.size >= 2, "重连后应重新 exec herdr，实际次数: ${created.size}")
        assertTrue(c.herdrExec != null, "重连后应有新的 exec 通道")
        assertEquals(0, bootstrapCount, "降级过的会话重连不应再引导 mosh-server")
        c.destroy()
    }

    @Test
    fun herdrExecReadErrorAutoReconnects() {
        // exec 读异常（引擎未吞成 EOF 的场景）：不经宽限直接按意外断开重连，不置灰
        val created = mutableListOf<KillableExec>()
        val fake = FakeSsh(
            commandHandler = { cmd ->
                when {
                    cmd.contains("api snapshot") -> snapshotJson
                    else -> null
                }
            },
            execFactory = { KillableExec().also(created::add) },
        )
        val c = TerminalController(herdrHost(), "pw", null, repo(), true) { _, cb ->
            fake.callbacks = cb
            fake
        }
        c.moshDegradedToSsh = true // 降级条目直走 exec（跳过 mosh 引导）
        c.connect(80, 24)
        awaitStatus(c, ConnStatus.CONNECTED)
        assertEquals(1, created.size)

        // 连接死亡且引擎读直接报错（非 EOF）
        fake.closed = true
        created[0].failRead = true

        awaitStatus(c, ConnStatus.CONNECTING)
        assertEquals(1, c.reconnectCount)
        awaitStatus(c, ConnStatus.CONNECTED)
        assertTrue(created.size >= 2)
        c.destroy()
    }

    @Test
    fun herdrExecNormalExitStillClosesSession() {
        // herdr 自己退出（EOF 且底层连接仍活）：工作台关闭——正常结束不重连
        val exec = KillableExec()
        val fake = FakeSsh(
            commandHandler = { cmd ->
                when {
                    cmd.contains("api snapshot") -> snapshotJson
                    else -> "no mosh-server here"
                }
            },
            execFactory = { exec },
        )
        val c = TerminalController(herdrHost(), "pw", null, repo(), true) { _, cb ->
            fake.callbacks = cb
            fake
        }
        c.moshDegradedToSsh = true // 降级条目直走 exec（跳过 mosh 引导）
        c.connect(80, 24)
        awaitStatus(c, ConnStatus.CONNECTED)

        exec.dead = true // herdr 退出，但 fake.closed=false（连接仍活）

        awaitStatus(c, ConnStatus.CLOSED)
        assertEquals(0, c.reconnectCount, "正常退出不应触发重连")
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
        c.moshDegradedToSsh = true // 降级条目直走 exec（跳过 mosh 引导）
        c.connect(80, 24)
        awaitStatus(c, ConnStatus.CONNECTED)

        // 探测按候选顺序（HerdrProbe 固定候选为裸命令）：herdr →
        // \$HOME/.local/bin/herdr → /usr/local/bin/herdr（命中）；降级 exec 为转义后路径
        assertEquals(3, probed.size)
        assertTrue(probed[0].startsWith("herdr "))
        assertTrue(probed[2].startsWith("/usr/local/bin/herdr "))
        // 命中路径贯穿降级 exec（非裸 herdr，单引号转义）
        assertEquals(listOf(shSingleQuote("/usr/local/bin/herdr")), f.execCommands)
        c.destroy()
    }

    @Test
    fun herdrProbeResolvesHomeCandidateToAbsolutePath() {
        // 真机踩坑（[REDACTED]）：sshd 非交互 exec PATH 不含 ~/.local/bin →
        // 裸 herdr 探测失败、$HOME 候选命中（经远端 shell 展开）；但下游
        // mosh 引导 `-- '<bin>'` 单引号不展开、mosh-server 子进程直接 execvp
        // 不过 shell，字面 $HOME 报
        // `execvp: $HOME/.local/bin/herdr: No such file or directory`。
        // 断言：命中后解析成绝对路径（echo $HOME），降级 exec 拿到转义后的绝对路径
        val commands = mutableListOf<String>()
        val fake = FakeSsh(
            commandHandler = { cmd ->
                commands += cmd
                when (cmd) {
                    "herdr api snapshot" -> null
                    "\$HOME/.local/bin/herdr api snapshot" -> snapshotJson
                    "echo \$HOME" -> "/root"
                    else -> null
                }
            },
            execFactory = { FakeExec() },
        )
        val (c, f) = herdrController(fake)
        c.moshDegradedToSsh = true // 降级条目直走 exec（跳过 mosh 引导）
        c.connect(80, 24)
        awaitStatus(c, ConnStatus.CONNECTED)

        assertTrue(commands.contains("echo \$HOME"), "命中 \$HOME 候选后应解析绝对路径")
        assertEquals(listOf(shSingleQuote("/root/.local/bin/herdr")), f.execCommands)
        c.destroy()
    }

    @Test
    fun herdrMoshBootstrapUsesResolvedAbsolutePath() {
        // mosh 引导命令必须带解析后的绝对路径：mosh-server 对 `--` 后参数直接
        // execvp（不过 shell），字面 $HOME 必失败（见上）。Main 换 Unconfined：
        // doConnectMosh 在引导解析后经 withContext(Main) 做主题准备，测试默认
        // 的 StandardTestDispatcher 不会自跑，会把连接协程挂死在那
        val bootstraps = mutableListOf<String>()
        val fake = FakeSsh(
            commandHandler = { cmd ->
                when {
                    cmd.contains("mosh-server new") -> {
                        bootstraps += cmd
                        // key 必须 22 字符规范 base64：客户端构造会校验，
                        // 非法 key 抛异常走 ERROR 分支到不了本断言
                        "MOSH CONNECT 60000 AAAAAAAAAAAAAAAAAAAAAA"
                    }
                    // exec PATH 无 ~/.local/bin：裸 herdr 失败，$HOME 候选命中
                    cmd == "herdr api snapshot" -> null
                    cmd == "\$HOME/.local/bin/herdr api snapshot" -> snapshotJson
                    cmd == "echo \$HOME" -> "/root"
                    else -> null
                }
            },
            execFactory = { FakeExec() },
        )
        Dispatchers.setMain(Dispatchers.Unconfined)
        val (c, f) = herdrController(fake)
        c.connect(80, 24)
        // 等引导命令发出即断言（不等 UDP 首包确认：那是 5s 超时路径，与本断言无关）
        runBlocking { withTimeout(5_000) { while (bootstraps.isEmpty()) delay(10) } }
        assertTrue(
            bootstraps[0].contains("-- ${shSingleQuote("/root/.local/bin/herdr")}"),
            "mosh 引导必须是解析后的绝对路径（非字面 \$HOME）: ${bootstraps[0]}",
        )
        c.destroy()
    }

    @Test
    fun herdrInstallMoshReGuidesWithHerdrArg() {
        // HERDR + mosh 未装 → 引导安装；安装完成后重新引导必须带 -- 'herdr'
        //（mosh 会话直接跑 herdr）。此处模拟装后 PATH 未刷新仍 not found →
        // 回到引导卡片，但引导命令必须已带上 herdr 参数
        var installed = false
        val bootstraps = mutableListOf<String>()
        val fake = FakeSsh(
            commandHandler = { cmd ->
                when {
                    cmd.contains("mosh-server new") -> {
                        bootstraps += cmd
                        "mosh: command not found"
                    }
                    cmd.contains("command -v mosh-server") -> if (installed) "/usr/bin/mosh-server" else null
                    cmd.contains("os-release") -> "ID=ubuntu"
                    cmd.contains("id -u") -> "0"
                    cmd.contains("api snapshot") -> snapshotJson
                    else -> null
                }
            },
            execFactory = { cmd ->
                if (cmd.contains("apt-get install -y mosh")) installed = true
                FakeExec(eof = true)
            },
        )
        val (c, f) = herdrController(fake)
        c.connect(80, 24)
        awaitStatus(c, ConnStatus.CONNECTED)
        assertTrue(c.moshNeedsInstall)

        c.installMosh()
        // 终态：安装完成 + 重引导仍 not found → 回到引导卡片（已带 herdr 参数）
        runBlocking {
            withTimeout(5_000) { while (!(!c.moshInstalling && c.moshNeedsInstall && bootstraps.size >= 2)) delay(10) }
        }

        assertEquals(2, bootstraps.size, "安装后应重新引导，实际: $bootstraps")
        assertTrue(bootstraps[1].contains("mosh-server new"), "引导命令: ${bootstraps[1]}")
        assertTrue(bootstraps[1].contains("-- ${shSingleQuote("herdr")}"), "HERDR 引导应带 -- herdr: ${bootstraps[1]}")
        c.destroy()
    }

    @Test
    fun herdrDegradeButtonRunsHerdrExecAndSticksForReconnect() {
        // HERDR + mosh 未装 → 卡片「降级 SSH」= exec+pty 跑 herdr；用户明确选择
        // 后置 sticky 标记，断链重连直走 herdr exec，不再引导 mosh
        var bootstrapCount = 0
        val created = mutableListOf<KillableExec>()
        val fake = FakeSsh(
            commandHandler = { cmd ->
                when {
                    cmd.contains("mosh-server new") -> {
                        bootstrapCount++
                        "mosh: command not found"
                    }
                    cmd.contains("api snapshot") -> snapshotJson
                    else -> null
                }
            },
            execFactory = { KillableExec().also(created::add) },
        )
        val c = TerminalController(herdrHost(), "pw", null, repo(), true) { _, cb ->
            fake.callbacks = cb
            fake
        }
        c.connect(80, 24)
        awaitStatus(c, ConnStatus.CONNECTED)
        assertTrue(c.moshNeedsInstall)

        // 用户点「降级 SSH」：直接 exec 跑 herdr
        c.degradeMoshToSsh()
        runBlocking { withTimeout(5_000) { while (created.isEmpty()) delay(10) } }
        assertEquals(listOf(shSingleQuote("herdr")), fake.execCommands)
        assertTrue(c.moshDegradedToSsh, "降级应置 sticky 标记")

        // 断链（连接死亡 + exec EOF）→ 自动重连 → 直走 herdr exec（无 mosh 引导）
        fake.closed = true
        created[0].dead = true
        awaitStatus(c, ConnStatus.CONNECTING)
        awaitStatus(c, ConnStatus.CONNECTED)

        assertEquals(1, bootstrapCount, "重连不应再次引导 mosh-server（首次引导除外）")
        assertTrue(created.size >= 2, "重连后应重建 herdr exec 通道")
        c.destroy()
    }

    @Test
    fun moshDegradedReconnectUsesPlainShell() {
        // MOSH 模式同样遵守 sticky 降级：重连不再引导 mosh-server，直接 SSH shell
        var bootstrapCount = 0
        val fake = FakeSsh(
            commandHandler = { cmd ->
                if (cmd.contains("mosh-server new")) {
                    bootstrapCount++
                    "MOSH CONNECT 60000 key"
                } else {
                    null
                }
            },
        )
        val (c, _) = moshController(fake)
        c.moshDegradedToSsh = true
        c.connect(80, 24)
        awaitStatus(c, ConnStatus.CONNECTED)

        assertEquals(0, bootstrapCount, "降级过的会话不应再引导 mosh-server")
        assertTrue(c.moshSession == null, "不应创建 mosh 会话")
        c.destroy()
    }

    // ---------- Mosh 引导安装 ----------

    @Test
    fun moshMissingGuidesInstall() {
        // Mosh 模式：mosh-server 缺失（not found）→ 保留 SSH 连接进入「待安装」
        //（引导卡片：安装或降级 SSH），不直接降级
        val fake = FakeSsh(
            commandHandler = { cmd ->
                when {
                    cmd.contains("mosh-server new") -> "mosh: command not found"
                    cmd.contains("os-release") -> "ID=ubuntu"
                    else -> null
                }
            },
        )
        val (c, f) = moshController(fake)
        c.connect(80, 24)
        awaitStatus(c, ConnStatus.CONNECTED)

        assertTrue(c.moshNeedsInstall, "应进入引导安装状态")
        assertTrue(!f.closed, "SSH 引导通道应保留（安装/降级都靠它）")
        c.destroy()
    }

    @Test
    fun installMoshRunsPackageManagerAndReGuides() {
        // 免密 sudo：安装命令（sudo -n apt-get…）执行 → command -v 验证通过 →
        // 重新引导仍缺（模拟装后 PATH 未刷新）→ 回到引导卡片（可重试/降级）
        var installed = false
        val fake = FakeSsh(
            commandHandler = { cmd ->
                when {
                    // bootstrap 命令串也含 os-release：mosh-server 分支必须在最前
                    cmd.contains("mosh-server new") -> "mosh: command not found"
                    cmd.contains("command -v mosh-server") ->
                        if (installed) "/usr/bin/mosh-server" else null
                    cmd.contains("os-release") -> "ID=ubuntu"
                    cmd.contains("id -u") -> "1000"
                    cmd.contains("command -v sudo") -> "/usr/bin/sudo"
                    cmd.contains("sudo -n true") -> "SUDO_OK"
                    else -> null
                }
            },
            execFactory = { cmd ->
                if (cmd.contains("apt-get install -y mosh")) installed = true
                FakeExec(eof = true)
            },
        )
        val (c, f) = moshController(fake)
        c.connect(80, 24)
        awaitStatus(c, ConnStatus.CONNECTED)
        assertTrue(c.moshNeedsInstall)

        c.installMosh()
        // 等组合终态而非仅 !moshInstalling：安装完会重引导（失败才回置
        // moshNeedsInstall），单等标志会抢在重引导完成前断言（flaky 根因）
        runBlocking { withTimeout(5_000) { while (!(!c.moshInstalling && c.moshNeedsInstall)) delay(10) } }

        assertTrue(installed, "应执行包管理器安装命令")
        assertTrue(f.execCommands.any { it.contains("sudo -n apt-get") }, "非 root 免密 sudo 前缀")
        assertTrue(c.moshNeedsInstall, "重引导仍缺 → 回到引导卡片（可重试/降级）")
        c.destroy()
    }

    @Test
    fun installMoshWithSudoPasswordFeedsStdin() {
        // 需要 sudo 密码：首次点击无密码 → 卡片提示输入；输入后安装命令走
        // `sudo -S` 且密码经 exec stdin 写入（不进命令字符串）
        var passwordSeen = ""
        val fake = FakeSsh(
            commandHandler = { cmd ->
                when {
                    cmd.contains("mosh-server new") -> "mosh: command not found"
                    cmd.contains("command -v mosh-server") -> "/usr/bin/mosh-server"
                    cmd.contains("os-release") -> "ID=debian"
                    cmd.contains("id -u") -> "1000"
                    cmd.contains("command -v sudo") -> "/usr/bin/sudo"
                    cmd.contains("sudo -n true") -> "sudo: a password is required"
                    else -> null
                }
            },
            execFactory = { cmd ->
                if (cmd.contains("sudo -S")) {
                    FakeExec(onWrite = { passwordSeen = it.decodeToString() }, eof = true)
                } else {
                    FakeExec(eof = true)
                }
            },
        )
        val (c, f) = moshController(fake)
        c.connect(80, 24)
        awaitStatus(c, ConnStatus.CONNECTED)
        assertTrue(c.moshNeedsInstall)

        // 首次点击（无密码）：探测发现需要 sudo 密码 → 卡片提示输入（终态 =
        // !moshInstalling && moshNeedsSudoPassword）
        c.installMosh()
        runBlocking { withTimeout(5_000) { while (!(!c.moshInstalling && c.moshNeedsSudoPassword)) delay(10) } }
        assertTrue(c.moshNeedsSudoPassword, "应提示输入 sudo 密码")
        assertTrue(!f.execCommands.any { it.contains("sudo -S") }, "无密码时不应执行安装")

        // 输入密码后重试：sudo -S 经 exec stdin 收密码（终态 = 安装全链路结束）
        c.installMosh("p@ss'w0rd")
        runBlocking { withTimeout(5_000) { while (!(!c.moshInstalling && !c.moshNeedsSudoPassword)) delay(10) } }

        assertTrue(f.execCommands.any { it.contains("sudo -S -p '' sh -c") }, "应走 sudo -S 非交互")
        assertEquals("p@ss'w0rd\n", passwordSeen, "密码经 stdin 送达，不进命令字符串")
        assertTrue(f.execCommands.none { it.contains("p@ss") }, "命令串不含密码明文")
        assertTrue(!c.moshNeedsSudoPassword, "安装成功应退出密码输入态")
        c.destroy()
    }
}

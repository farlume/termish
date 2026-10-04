package dev.termish.screen

import dev.termish.ssh.AuthPrompt
import dev.termish.ssh.HostKeyInfo
import dev.termish.ssh.SshCallbacks
import dev.termish.ssh.SshConnection
import dev.termish.ssh.createSshSession
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue

/**
 * 屏幕推流链路集成测试：本地 sshd（127.0.0.1:22222）+ 本机 ffmpeg 抓屏，
 * 走 ScreenSession 同款 exec raw 通道。回归两个真实 bug：
 * 1. stderr 阻塞读卡死主循环（黑屏）——stderr 拆独立协程后 stdout 必须持续消费
 * 2. 远端 ffmpeg 退出即「画面流已断开」——通道 5 秒内不应 EOF
 *
 * 环境缺任一依赖（sshd/私钥/ffmpeg）自动 SKIP（与传输层集成测试同模式）。
 */
class ScreenStreamIntegrationTest {
    private fun env(
        key: String,
        default: String,
    ): String = System.getenv(key) ?: default

    private fun sshdReachable(): Boolean =
        runCatching {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", env("Termish_TEST_PORT", "22222").toInt()), 500) }
        }.isSuccess

    private fun ffmpegAvailable(): Boolean =
        runCatching {
            val p = ProcessBuilder("sh", "-c", "command -v ffmpeg").redirectErrorStream(true).start()
            val out =
                p.inputStream
                    .readBytes()
                    .decodeToString()
                    .trim()
            p.waitFor()
            out.isNotEmpty()
        }.getOrDefault(false)

    private fun skipUnlessReady(): Boolean {
        if (!skipUnlessSshReady()) return false
        if (!ffmpegAvailable()) {
            println("SKIP: 本机无 ffmpeg（远端抓屏脚本需要）")
            return false
        }
        return true
    }

    private fun skipUnlessSshReady(): Boolean {
        if (!sshdReachable()) {
            println("SKIP: 测试 sshd 未启动（scripts/test-sshd.sh 或 make test-integration）")
            return false
        }
        val pemFile = File(env("Termish_TEST_KEY", "/tmp/termish_test/client"))
        if (!pemFile.exists()) {
            println("SKIP: no key at ${pemFile.absolutePath}")
            return false
        }
        return true
    }

    @Test
    fun `raw exec channel streams h264 without stderr blocking`() {
        if (!skipUnlessReady()) return
        val pemFile = File(env("Termish_TEST_KEY", "/tmp/termish_test/client"))
        val session =
            createSshSession(
                SshConnection(
                    host = "127.0.0.1",
                    port = env("Termish_TEST_PORT", "22222").toInt(),
                    username = checkNotNull(System.getProperty("user.name")),
                    privateKeyPem = pemFile.readText(),
                ),
                object : SshCallbacks {
                    override suspend fun onOutput(data: ByteArray) {}

                    override suspend fun onStderr(data: ByteArray) {}

                    override fun onExitStatus(status: Int) {}

                    override fun onClosed(reason: String?) {}

                    override suspend fun onPrompt(prompt: AuthPrompt): List<String>? = null

                    override fun verifyHostKey(hostKey: HostKeyInfo): Boolean = true
                },
            )
        try {
            assertNotNull(session.connectAuthOnly(), "connectAuthOnly 应成功")
            val ch = session.startExecRaw(ScreenSession.LAVFI_SCRIPT)
            assertNotNull(ch, "startExecRaw 应返回通道")

            // stderr 独立协程（ScreenSession 同款模式）：消费但不能阻塞 stdout
            val stderrBuf = StringBuilder()
            val stderrJob =
                CoroutineScope(Dispatchers.IO).launch {
                    while (true) {
                        val err = ch.readErr() ?: break
                        stderrBuf.append(err.decodeToString())
                    }
                }

            // 主循环：阻塞读 stdout + NAL 计数，跑 5 秒。
            // 用 lavfi testsrc 源（不依赖屏幕录制权限——sshd 会话下 avfoundation
            // 抓屏会因 TCC 权限失败/挂起，那是环境问题不是链路问题）
            val parser = H264Stream.AnnexBParser()
            var nalCount = 0
            var sawSps = false
            var sawIdr = false
            var sawAud = false
            var eofEarly = false
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline) {
                val data = ch.read()
                if (data == null) {
                    eofEarly = true
                    break
                }
                var nal = parser.push(data)
                while (nal != null) {
                    nalCount++
                    if (nal.type == 7) sawSps = true
                    if (nal.type == 5) sawIdr = true
                    // AUD（type 9）：帧对齐标记——relay 按 AUD 切帧，丢一片只丢一帧
                    if (nal.type == 9) sawAud = true
                    nal = parser.drain()
                }
            }
            stderrJob.cancel()

            val errText = stderrBuf.toString()
            println(
                "--- screen stream: nals=$nalCount sps=$sawSps idr=$sawIdr aud=$sawAud eofEarly=$eofEarly stderr=${errText.take(
                    300,
                )}",
            )
            assertFalse(eofEarly, "5 秒内通道不应 EOF（远端 ffmpeg 正常推流），stderr: $errText")
            assertTrue(nalCount > 10, "5 秒内应收到大量 NAL，实际 $nalCount（疑似 stdout 被 stderr 阻塞读卡死）")
            assertTrue(sawSps && sawIdr, "应收到 SPS + IDR 关键帧（sps=$sawSps idr=$sawIdr）")
            assertTrue(sawAud, "流应含 AUD（帧对齐语义；LAVFI_SCRIPT 已加 aud=1）")
            assertFalse(errText.contains("FFMPEG_MISSING"), "远端不应报 ffmpeg 缺失")
        } finally {
            session.close()
        }
    }

    @Test
    fun `ssh direct tcpip channel is bidirectional`() {
        if (!skipUnlessSshReady()) return
        val server = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        val serverJob =
            thread(name = "direct-tcpip-test-server") {
                server.accept().use { client ->
                    val request = client.getInputStream().readNBytes(4)
                    if (request.contentEquals("ping".encodeToByteArray())) {
                        client.getOutputStream().write("pong".encodeToByteArray())
                        client.getOutputStream().flush()
                    }
                }
            }
        val pemFile = File(env("Termish_TEST_KEY", "/tmp/termish_test/client"))
        val session = createTestSession(pemFile)
        try {
            assertNotNull(session.connectAuthOnly(), "connectAuthOnly 应成功")
            val channel = session.openDirectTcpip("127.0.0.1", server.localPort)
            assertNotNull(channel, "direct-tcpip 通道应建立")
            channel.write("ping".encodeToByteArray())
            assertContentEquals("pong".encodeToByteArray(), channel.read(), "通道应可双向收发")
            channel.close()
        } finally {
            session.close()
            server.close()
            serverJob.join(2_000)
        }
    }

    /**
     * 手机端一键安装链路回归：实际执行 INSTALL_SCRIPT（引导卡片同款）→
     * 服务装好并监听 → READ_STREAM_SCRIPT 读流持续出帧。
     * 仅 macOS 远端有效（LaunchAgent GUI 域机制），收尾自动卸载服务。
     */
    @Test
    fun `install script sets up gui session service and serves h264 stream`() {
        assumeTrue(
            "SKIP: 安装/卸载真实桌面服务需显式设置 TERMISH_TEST_INSTALL_SCREEN=1",
            System.getenv("TERMISH_TEST_INSTALL_SCREEN") == "1",
        )
        if (!skipUnlessReady()) return
        if (!System.getProperty("os.name").orEmpty().contains("Mac")) {
            println("SKIP: 推流服务安装脚本仅支持 macOS 远端")
            return
        }
        val pemFile = File(env("Termish_TEST_KEY", "/tmp/termish_test/client"))
        val session =
            createSshSession(
                SshConnection(
                    host = "127.0.0.1",
                    port = env("Termish_TEST_PORT", "22222").toInt(),
                    username = checkNotNull(System.getProperty("user.name")),
                    privateKeyPem = pemFile.readText(),
                ),
                object : SshCallbacks {
                    override suspend fun onOutput(data: ByteArray) {}

                    override suspend fun onStderr(data: ByteArray) {}

                    override fun onExitStatus(status: Int) {}

                    override fun onClosed(reason: String?) {}

                    override suspend fun onPrompt(prompt: AuthPrompt): List<String>? = null

                    override fun verifyHostKey(hostKey: HostKeyInfo): Boolean = true
                },
            )
        try {
            assertNotNull(session.connectAuthOnly())
            // 1. 跑安装脚本：应输出 TERMISH_SCREEN_OK
            val installOut = StringBuilder()
            val installCh = session.startExecRaw(ScreenSession.INSTALL_SCRIPT)
            assertNotNull(installCh, "安装通道应建立")
            while (true) {
                val d = installCh.read() ?: break
                installOut.append(d.decodeToString())
            }
            installCh.close()
            println("--- install output: ${installOut.toString().lines().lastOrNull()}")
            assertTrue(
                installOut.toString().contains("TERMISH_SCREEN_OK"),
                "安装脚本应成功：${installOut.toString().take(300)}",
            )
            // relay 预热 + 首次连接需要几秒，等 2 秒再读流
            Thread.sleep(2_000)

            // 2. 控制面只返回远端回环 TCP 端口；视频面用 SSH direct-tcpip
            // 取得 THS1 状态 + 长度分帧的 H.264 Annex-B，与手机端实际链路一致。
            val preflight = session.runCommandDetailed(ScreenSession.READ_STREAM_SCRIPT, 15_000)
            assertNotNull(preflight, "控制面探测应成功")
            assumeFalse(
                "SKIP: 本机桌面锁屏或休眠，无法验证真实抓屏",
                preflight.stderr.lineSequence().any { it == "SCREEN_ASLEEP" || it == "SCREEN_LOCKED" },
            )
            assertFalse(preflight.stderr.contains("SCREEN_"), "控制面不应报错：${preflight.stderr}")
            val tcpPort =
                preflight.stdout
                    .lineSequence()
                    .firstOrNull { it.startsWith("SCREEN_TCP_PORT:") }
                    ?.substringAfter(":")
                    ?.trim()
                    ?.toIntOrNull()
            // stdout 同时携带短生命周期之外也应保密的 bearer token，失败信息
            // 不能把整段探测输出写进本地/CI 测试日志。
            assertNotNull(tcpPort, "应返回 TCP 视频端口")
            val authToken = ScreenSession.parseAuthToken(preflight.stdout)
            assertNotNull(authToken, "应通过已认证 SSH 返回画面 token")
            val streamCh = session.openDirectTcpip("127.0.0.1", tcpPort)
            assertNotNull(streamCh, "SSH direct-tcpip 视频通道应建立")
            streamCh.write(frameScreenTcpAuth(authToken))
            var bytes = 0
            var frames = 0
            val parser =
                ScreenTcpFrameParser({}, { frame ->
                    bytes += frame.size
                    frames++
                })
            val deadline = System.currentTimeMillis() + 8_000
            while (System.currentTimeMillis() < deadline) {
                val data = streamCh.read() ?: break
                parser.push(data)
                if (frames >= 10 && bytes > 50_000) break
            }
            streamCh.close()
            println("--- direct-tcpip stream: frames=$frames bytes=$bytes")
            assertTrue(frames >= 10, "推流服务应持续产生完整帧，实际 $frames 帧")
            assertTrue(bytes > 50_000, "推流服务应持续出流，实际 $bytes 字节")
        } finally {
            // 收尾：卸载服务（测试不留垃圾）
            runCatching {
                session.runCommand(
                    "launchctl bootout gui/\$(id -u) \$HOME/Library/LaunchAgents/dev.termish.screen.plist",
                    10_000,
                )
            }
            session.close()
        }
    }

    private fun createTestSession(pemFile: File) =
        createSshSession(
            SshConnection(
                host = "127.0.0.1",
                port = env("Termish_TEST_PORT", "22222").toInt(),
                username = checkNotNull(System.getProperty("user.name")),
                privateKeyPem = pemFile.readText(),
            ),
            object : SshCallbacks {
                override suspend fun onOutput(data: ByteArray) {}

                override suspend fun onStderr(data: ByteArray) {}

                override fun onExitStatus(status: Int) {}

                override fun onClosed(reason: String?) {}

                override suspend fun onPrompt(prompt: AuthPrompt): List<String>? = null

                override fun verifyHostKey(hostKey: HostKeyInfo): Boolean = true
            },
        )
}

class SystemSshdStreamTest {
    @Test
    fun `sshj reads stream via system sshd port 22`() {
        if (!System.getProperty("os.name").orEmpty().contains("Mac")) {
            println("SKIP: 仅 macOS")
            return
        }
        val pemFile = File("/tmp/termish_test/client_600")
        if (!pemFile.exists()) {
            println("SKIP: no key at ${pemFile.absolutePath}")
            return
        }
        val session =
            createSshSession(
                SshConnection(
                    host = "127.0.0.1",
                    port = 22,
                    username = checkNotNull(System.getProperty("user.name")),
                    privateKeyPem = pemFile.readText(),
                ),
                object : SshCallbacks {
                    override suspend fun onOutput(data: ByteArray) {}

                    override suspend fun onStderr(data: ByteArray) {}

                    override fun onExitStatus(status: Int) {}

                    override fun onClosed(reason: String?) {}

                    override suspend fun onPrompt(prompt: AuthPrompt): List<String>? = null

                    override fun verifyHostKey(hostKey: HostKeyInfo): Boolean = true
                },
            )
        try {
            val conn = session.connectAuthOnly()
            assertNotNull(conn, "系统 sshd 连接失败")
            // LAVFI 自包含推流（不依赖 relay 服务：install 测试收尾会卸载服务）
            val ch = session.startExecRaw(ScreenSession.LAVFI_SCRIPT)
            assertNotNull(ch, "exec 通道建立失败")
            val parser = H264Stream.AnnexBParser()
            var nalCount = 0
            var eof = false
            val deadline = System.currentTimeMillis() + 4_000
            while (System.currentTimeMillis() < deadline) {
                val data = ch.read()
                if (data == null) {
                    eof = true
                    break
                }
                var nal = parser.push(data)
                while (nal != null) {
                    nalCount++
                    nal = parser.drain()
                }
            }
            println("--- system sshd stream: nals=$nalCount eof=$eof")
            assertTrue(nalCount > 10, "系统 sshd 下应读到流，实际 nals=$nalCount eof=$eof")
        } finally {
            session.close()
        }
    }
}

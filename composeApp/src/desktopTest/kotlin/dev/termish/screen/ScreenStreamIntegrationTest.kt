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
                    username = System.getProperty("user.name"),
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
        if (!skipUnlessReady()) return
        if (!System.getProperty("os.name").contains("Mac")) {
            println("SKIP: 推流服务安装脚本仅支持 macOS 远端")
            return
        }
        val pemFile = File(env("Termish_TEST_KEY", "/tmp/termish_test/client"))
        val session =
            createSshSession(
                SshConnection(
                    host = "127.0.0.1",
                    port = env("Termish_TEST_PORT", "22222").toInt(),
                    username = System.getProperty("user.name"),
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
        }
    }

    @Test
    fun `relay python script is syntactically valid`() {
        val py3 =
            runCatching {
                val p = ProcessBuilder("sh", "-c", "command -v python3").redirectErrorStream(true).start()
                val out =
                    p.inputStream
                        .readBytes()
                        .decodeToString()
                        .trim()
                p.waitFor()
                out
            }.getOrDefault("")
        if (py3.isEmpty()) {
            println("SKIP: 无 python3（relay 语法检查需要）")
            return
        }
        // INSTALL_SCRIPT 的 heredoc 内容（bash 变量 $PORT/$FF_REAL 安装时注入）
        val script = ScreenSession.INSTALL_SCRIPT
        val start = script.indexOf("#!/usr/bin/env python3")
        assertTrue(start >= 0, "INSTALL_SCRIPT 应含 python relay")
        var py = script.substring(start, script.indexOf("TERMISH_EOF", start))
        // Kotlin 模板转义还原 + dedent + bash 注入变量
        py =
            py
                .replace("${'$'}{'\$'}", "$")
                .replace("\$PORT", "17321")
                .replace("\$FF_REAL", "/usr/bin/ffmpeg")
        // 逐行去公共缩进（heredoc 内嵌 Kotlin 字符串的 12 空格缩进）
        val lines = py.lines()
        val minIndent = lines.filter { it.isNotBlank() }.minOf { it.takeWhile { c -> c == ' ' }.length }
        py = lines.joinToString("\n") { if (it.length >= minIndent) it.substring(minIndent) else it }

        val tmp = File.createTempFile("relay", ".py")
        tmp.writeText(py)
        try {
            val p =
                ProcessBuilder(py3, "-m", "py_compile", tmp.absolutePath)
                    .redirectErrorStream(true)
                    .start()
            val err = p.inputStream.readBytes().decodeToString()
            val code = p.waitFor()
            assertTrue(code == 0, "relay python 语法错误:\n$err")
        } finally {
            tmp.delete()
        }
        // 关键语义断言：视频口仅回环 + TCP 显式分帧 + 硬编（防脚本漂移）
        assertTrue(py.contains("TCP_VIDEO_PORT = 17321 + 2"), "relay 应定义独立 TCP 视频端口")
        assertTrue(py.contains("srv.bind((\"127.0.0.1\", TCP_VIDEO_PORT))"), "视频口必须只监听回环")
        assertTrue(py.contains("struct.pack(\">I\", len(frame))"), "TCP 视频必须显式分帧")
        assertTrue(py.contains("recv_exact(conn, 4)"), "TCP 控制包必须显式分帧")
        assertTrue(py.contains("AUTH_MAGIC = b\"THA1\""), "TCP 视频通道必须先做 token 握手")
        assertTrue(py.contains("hmac.compare_digest(auth[4:], AUTH_TOKEN)"), "token 比较必须使用恒定时间实现")
        assertTrue(py.contains("h264_videotoolbox"), "macOS 分支应硬编")
        assertTrue(py.contains("gop = max(15, fps // 2)"), "GOP 应按帧率保持约 0.5s")
        assertTrue(py.contains("\"-g\", str(gop)"), "ffmpeg 应使用动态 GOP")
        assertTrue(py.contains("RELAY_VERSION = ${ScreenSession.RELAY_VERSION}"), "relay 版本应与客户端一致")
        assertTrue(py.contains("elif typ == 8 or typ == 9:"), "虚拟鼠标点击必须使用独立原子事件")
        assertTrue(py.contains("CGEventGetLocation(current_event)"), "虚拟点击后必须恢复实体鼠标位置")
        assertTrue(py.contains("CGEventSetLocation(ev, (px, py))"), "滚轮应定位到虚拟箭头且不移动实体指针")
        assertTrue(py.contains("FF_PIDFILE"), "relay 应只按自身 PID 文件清理孤儿 ffmpeg")
        assertFalse(py.contains("subprocess.run([\"pkill\""), "relay 不得误杀用户的其它 ffmpeg 进程")
    }

    private fun createTestSession(pemFile: File) =
        createSshSession(
            SshConnection(
                host = "127.0.0.1",
                port = env("Termish_TEST_PORT", "22222").toInt(),
                username = System.getProperty("user.name"),
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
        if (!System.getProperty("os.name").contains("Mac")) {
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
                    username = System.getProperty("user.name"),
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

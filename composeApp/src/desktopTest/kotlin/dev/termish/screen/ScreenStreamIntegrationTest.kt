package dev.termish.screen

import dev.termish.ssh.AuthPrompt
import dev.termish.ssh.HostKeyInfo
import dev.termish.ssh.SshCallbacks
import dev.termish.ssh.SshConnection
import dev.termish.ssh.createSshSession
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.test.Test
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
        if (!sshdReachable()) {
            println("SKIP: 测试 sshd 未启动（scripts/test-sshd.sh 或 make test-integration）")
            return false
        }
        val pemFile = File(env("Termish_TEST_KEY", "/tmp/termish_test/client"))
        if (!pemFile.exists()) {
            println("SKIP: no key at ${pemFile.absolutePath}")
            return false
        }
        if (!ffmpegAvailable()) {
            println("SKIP: 本机无 ffmpeg（远端抓屏脚本需要）")
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

            // 2. 读流脚本（手机端实际链路）：relay 出 H.264 Annex-B（AUD 帧对齐），
            // 持续收字节即可（UDP 直连链路由 ScreenStreamUdpSessionTest 覆盖）
            val streamCh = session.startExecRaw(ScreenSession.READ_STREAM_SCRIPT)
            assertNotNull(streamCh, "读流通道应建立")
            var bytes = 0
            val deadline = System.currentTimeMillis() + 8_000
            while (System.currentTimeMillis() < deadline) {
                val data = streamCh.read() ?: break
                bytes += data.size
            }
            streamCh.close()
            println("--- read stream: bytes=$bytes")
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
        // 关键语义断言：双栈 + 硬编 + GOP（与 ScreenSession 改动同源，防漂移）
        assertTrue(py.contains("AF_INET6") && py.contains("IPV6_V6ONLY"), "relay 应为 IPv6 双栈")
        assertTrue(py.contains("h264_videotoolbox"), "macOS 分支应硬编")
        assertTrue(py.contains("\"-g\", \"15\""), "GOP 应为 0.5s（-g 15）")
        assertTrue(py.contains("RELAY_VERSION = ${ScreenSession.RELAY_VERSION}"), "relay 版本应与客户端一致")
    }
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

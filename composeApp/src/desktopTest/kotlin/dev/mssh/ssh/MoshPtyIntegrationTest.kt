package dev.mssh.ssh

import com.pty4j.PtyProcessBuilder
import com.pty4j.WinSize
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * 桌面 Mosh（pty4j）集成测试：本地启动 mosh-server，用与 [JvmMoshSession]
 * 完全相同的 pty4j 调用拉起 mosh-client，验证 PTY 尺寸、连接与回显。
 *
 * 本机未安装 mosh（brew install mosh / apt install mosh）时自动跳过。
 */
class MoshPtyIntegrationTest {

    private fun findMoshClient(): String? {
        for (candidate in listOf(
            "/opt/homebrew/bin/mosh-client",
            "/usr/local/bin/mosh-client",
            "/usr/bin/mosh-client",
        )) {
            if (File(candidate).canExecute()) return candidate
        }
        // PATH 查找（CI Linux runner 等情况）
        return runCatching {
            ProcessBuilder("mosh-client", "--version").redirectErrorStream(true).start().waitFor()
            "mosh-client"
        }.getOrNull()
    }

    @Test
    fun moshClientConnectsViaPty() {
        val moshClient = findMoshClient()
        if (moshClient == null) {
            println("SKIP: 未找到 mosh-client（brew install mosh / apt install mosh）")
            return
        }

        // 1. 前台启动 mosh-server（-s 不 daemon 化，随测试进程退出）
        //    -i 127.0.0.1：macOS 上 mosh-server 默认绑 IPv6 双栈，IPv4 客户端
        //    连 127.0.0.1 会报 "Nothing received from server"，显式绑 IPv4 保证两端一致
        val server = ProcessBuilder(
            moshClient.replace("mosh-client", "mosh-server"),
            "new", "-s", "-i", "127.0.0.1", "-c", "8", "-l", "LANG=en_US.UTF-8",
        ).redirectErrorStream(true).start()

        val reader = server.inputStream.bufferedReader()
        var line: String?
        var port = ""
        var key = ""
        while (true) {
            line = reader.readLine()
                ?: throw AssertionError("mosh-server 提前退出")
            if (line.contains("MOSH CONNECT")) {
                val parts = line.split(" ")
                port = parts[2]
                key = parts[3]
                break
            }
        }

        // 2. pty4j 拉起 mosh-client（与 JvmMoshSession 相同的调用方式）
        val env = HashMap<String, String>()
        System.getenv().forEach { (k, v) -> env[k] = v }
        env["MOSH_KEY"] = key
        env["TERM"] = "xterm-256color"

        val client = PtyProcessBuilder(arrayOf(moshClient, "127.0.0.1", port))
            .setInitialColumns(80)
            .setInitialRows(30)
            .setEnvironment(env)
            .start()

        val all = StringBuilder()
        val deadline = System.currentTimeMillis() + 25_000
        var echoed = false
        var done = false
        var sent = System.currentTimeMillis() + 2_500 // 先给握手一点时间
        val buf = ByteArray(16 * 1024)
        val readerThread = Thread({
            try {
                while (true) {
                    val n = client.inputStream.read(buf)
                    if (n <= 0) break
                    synchronized(all) { all.append(String(buf, 0, n, Charsets.UTF_8)) }
                }
            } catch (_: Exception) {
            }
        }, "mosh-test-reader")
        readerThread.start()
        try {
            while (!done && System.currentTimeMillis() < deadline) {
                if (!echoed && System.currentTimeMillis() > sent) {
                    client.outputStream.write("echo desktop-mosh-ok\n".toByteArray())
                    client.outputStream.flush()
                    echoed = true
                    sent = 0
                }
                synchronized(all) {
                    if (echoed && all.contains("desktop-mosh-ok")) done = true
                }
                Thread.sleep(50)
            }
        } finally {
            client.destroy()
            server.destroy()
            readerThread.interrupt()
        }

        println("=== mosh 输出（节选） ===")
        synchronized(all) {
            println(all.take(2000))
            assertTrue(all.contains("desktop-mosh-ok"), "未收到回显，输出: ${all.take(500)}")
        }
    }
}

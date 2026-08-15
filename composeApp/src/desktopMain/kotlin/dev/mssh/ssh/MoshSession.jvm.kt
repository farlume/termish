package dev.mssh.ssh

import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import com.pty4j.WinSize
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

suspend actual fun createMoshClient(
    ip: String,
    port: Int,
    key: String,
    columns: Int,
    rows: Int,
    onOutput: (ByteArray) -> Unit,
    onExit: () -> Unit,
): MoshSession = JvmMoshSession(ip, port, key, columns, rows, onOutput, onExit)

/**
 * JVM 实现（桌面）：用 pty4j 创建真正的 PTY 并启动系统 mosh-client
 * （brew/apt 安装的 mosh）。支持实时 resize（pty4j 会向 mosh-client 触发 SIGWINCH）。
 */
private class JvmMoshSession(
    ip: String,
    port: Int,
    key: String,
    columns: Int,
    rows: Int,
    private val onOutput: (ByteArray) -> Unit,
    private val onExit: () -> Unit,
) : MoshSession {

    private val active = AtomicBoolean(true)
    private var process: PtyProcess? = null

    init {
        val p = PtyProcessBuilder(arrayOf(findMoshClient(), ip, port.toString()))
            .setInitialColumns(columns)
            .setInitialRows(rows)
            .setEnvironment(moshEnv(key))
            .start()
        process = p

        Thread({
            val buf = ByteArray(64 * 1024)
            try {
                while (active.get()) {
                    val n = p.inputStream.read(buf)
                    if (n <= 0) break
                    onOutput(buf.copyOf(n))
                }
            } catch (_: Exception) {
            } finally {
                if (active.getAndSet(false)) onExit()
            }
        }, "mosh-reader").start()
    }

    override fun isActive(): Boolean = active.get() && (process?.isAlive ?: false)

    override fun resize(columns: Int, rows: Int) {
        try {
            process?.setWinSize(WinSize(columns, rows))
        } catch (_: Exception) {
        }
    }

    override fun sendData(data: ByteArray) {
        try {
            process?.outputStream?.write(data)
            process?.outputStream?.flush()
        } catch (_: Exception) {
        }
    }

    override fun close() {
        if (!active.getAndSet(false)) return
        try {
            process?.destroy()
        } catch (_: Exception) {
        }
        process = null
        onExit()
    }

    private fun moshEnv(key: String): Map<String, String> {
        val env = HashMap<String, String>()
        System.getenv().forEach { (k, v) -> env[k] = v }
        env["MOSH_KEY"] = key
        env["TERM"] = "xterm-256color"
        return env
    }

    private fun findMoshClient(): String {
        for (candidate in listOf(
            "/opt/homebrew/bin/mosh-client",
            "/usr/local/bin/mosh-client",
            "/usr/bin/mosh-client",
        )) {
            if (File(candidate).canExecute()) return candidate
        }
        return "mosh-client"
    }
}

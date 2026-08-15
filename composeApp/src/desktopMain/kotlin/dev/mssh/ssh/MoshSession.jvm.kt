package dev.mssh.ssh

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
 * JVM 实现（桌面）：直接 exec 系统 mosh-client（brew/apt 安装的 mosh）。
 * 用 `script -q /dev/null` 包一层提供 PTY（macOS/Linux 自带），
 * app 通过管道读写。
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
    private var process: Process? = null

    init {
        val cmd = listOf(
            "script", "-q", "/dev/null", "-c",
            "stty rows $rows cols $columns; mosh-client $ip $port",
        )
        val pb = ProcessBuilder(cmd)
        pb.environment()["MOSH_KEY"] = key
        pb.environment()["TERM"] = "xterm-256color"
        process = pb.start()

        Thread({
            val buf = ByteArray(64 * 1024)
            try {
                while (active.get()) {
                    val n = process?.inputStream?.read(buf) ?: -1
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
        // script 包装下无法直接改 PTY 尺寸；通过向 tty 发送 SIGWINCH 的方式不可行，
        // 桌面端保持初始尺寸（mosh 会话内可由远端 resize 处理）。
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
        process?.destroy()
        onExit()
    }
}

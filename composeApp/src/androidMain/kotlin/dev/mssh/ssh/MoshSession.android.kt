package dev.mssh.ssh

import dev.mssh.AppContext
import dev.mssh.generated.resources.Res
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking

/** libmoshpty.so：Android 无 Java 级 PTY API，用 bionic openpty 桥接。 */
private object MoshPty {
    init {
        System.loadLibrary("moshpty")
    }

    external fun openPty(fds: IntArray, rows: Int, cols: Int): String?
    external fun resizePty(masterFd: Int, rows: Int, cols: Int): Int
    external fun closePty(masterFd: Int, slaveFd: Int)
    external fun readPty(masterFd: Int, buf: ByteArray): Int
    external fun writePty(masterFd: Int, buf: ByteArray, len: Int): Int
}

suspend actual fun createMoshClient(
    ip: String,
    port: Int,
    key: String,
    columns: Int,
    rows: Int,
    onOutput: (ByteArray) -> Unit,
    onExit: () -> Unit,
): MoshSession = AndroidMoshSession(ip, port, key, columns, rows, onOutput, onExit)

/**
 * Android 实现：把 APK 里的 mosh-client / libc++_shared / terminfo 解压到
 * filesDir，openpty 创建 PTY，mosh-client 的 stdin/stdout/stderr 重定向到
 * slave，app 通过 master fd 读写。
 */
private class AndroidMoshSession(
    private val ip: String,
    private val port: Int,
    private val key: String,
    columns: Int,
    rows: Int,
    private val onOutput: (ByteArray) -> Unit,
    private val onExit: () -> Unit,
) : MoshSession {

    private val active = AtomicBoolean(true)
    private var process: Process? = null
    private var masterFd = -1
    private var slaveFd = -1

    init {
        val ctx = AppContext.get()
        val dir = File(ctx.filesDir, "mosh").apply { mkdirs() }
        extractTerminfo(File(dir, "terminfo"))
        // 原生库目录（useLegacyPackaging=true 后系统解压到此处），SELinux 允许 app 执行
        val nativeDir = File(ctx.applicationInfo.nativeLibraryDir)
        val clientBin = File(nativeDir, "libmoshclient.so")
        if (!clientBin.exists()) throw IllegalStateException("mosh-client 未解压: $clientBin")

        val fds = IntArray(2)
        val slavePath = MoshPty.openPty(fds, rows, columns)
            ?: throw IllegalStateException("openpty 失败")
        masterFd = fds[0]
        slaveFd = fds[1]

        val pb = ProcessBuilder(
            "/system/bin/sh", "-c",
            "exec \"$clientBin\" \"$ip\" \"$port\"",
        )
            .redirectInput(ProcessBuilder.Redirect.from(File(slavePath)))
            .redirectOutput(ProcessBuilder.Redirect.to(File(slavePath)))
            .redirectError(ProcessBuilder.Redirect.to(File(slavePath)))
        val env = pb.environment()
        env["MOSH_KEY"] = key
        env["TERM"] = "xterm-256color"
        env["TERMINFO"] = File(dir, "terminfo").absolutePath
        env["LD_LIBRARY_PATH"] = nativeDir.absolutePath
        pb.redirectError(File(dir, "mosh-stderr.log"))
        process = pb.start()

        Thread({
            val buf = ByteArray(64 * 1024)
            try {
                while (active.get()) {
                    val n = MoshPty.readPty(masterFd, buf)
                    if (n <= 0) break
                    onOutput(buf.copyOf(n))
                }
            } catch (_: Exception) {
            } finally {
                if (active.getAndSet(false)) onExit()
            }
        }, "mosh-reader").start()
    }

    private fun extractTerminfo(dir: File) {
        // 经典布局：terminfo/<首字母>/<终端名>
        val entries = listOf(
            "x/xterm-256color", "x/xterm", "s/screen", "v/vt100",
        )
        for (path in entries) {
            val out = File(dir, path)
            if (out.exists()) continue
            try {
                val bytes = runBlocking { Res.readBytes("files/terminfo/$path") }
                out.parentFile?.mkdirs()
                out.writeBytes(bytes)
            } catch (_: Exception) {
            }
        }
    }

    override fun isActive(): Boolean = active.get() && (process?.isAlive ?: false)

    override fun resize(columns: Int, rows: Int) {
        if (masterFd >= 0) MoshPty.resizePty(masterFd, rows, columns)
    }

    override fun sendData(data: ByteArray) {
        try {
            MoshPty.writePty(masterFd, data, data.size)
        } catch (_: Exception) {
        }
    }

    override fun close() {
        if (!active.getAndSet(false)) return
        process?.destroy()
        MoshPty.closePty(masterFd, slaveFd)
        masterFd = -1
        slaveFd = -1
        onExit()
    }

}

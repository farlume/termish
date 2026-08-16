@file:OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)

package dev.mssh.ssh

import dev.mssh.generated.resources.Res
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import moshpty.mssh_close
import moshpty.mssh_openpty
import moshpty.mssh_read
import moshpty.mssh_resize
import moshpty.mssh_spawn
import moshpty.mssh_write
import platform.Foundation.NSFileManager
import platform.Foundation.NSBundle
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSTemporaryDirectory
import platform.posix.AF_INET
import platform.posix.SOCK_DGRAM
import platform.posix.addrinfo
import platform.posix.freeaddrinfo
import platform.posix.getaddrinfo
import platform.posix.O_CREAT
import platform.posix.O_RDWR
import platform.posix.O_TRUNC
import platform.posix.SIGTERM
import platform.posix.chmod
import platform.posix.close
import platform.posix.kill
import platform.posix.open
import platform.posix.pid_t
import platform.posix.sockaddr_in
import platform.posix.waitpid
import platform.posix.write
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.AtomicBoolean

suspend actual fun createMoshClient(
    ip: String,
    port: Int,
    key: String,
    columns: Int,
    rows: Int,
    onOutput: (ByteArray) -> Unit,
    onExit: () -> Unit,
): MoshSession = IosMoshSession(ip, port, key, columns, rows, onOutput, onExit)

/**
 * iOS 实现：从 app bundle 取出交叉编译的 mosh-client（sim/device 各一份），
 * 用 C 桥接（moshpty_ios.c）创建 PTY 并 posix_spawn，app 通过 master fd 读写。
 * 代码签名由 Xcode 对 bundle 内二进制统一处理，运行前复制到 tmp 并保持签名。
 */
private class IosMoshSession(
    private val ip: String,
    private val port: Int,
    private val key: String,
    columns: Int,
    rows: Int,
    private val onOutput: (ByteArray) -> Unit,
    private val onExit: () -> Unit,
) : MoshSession {

    private val active = AtomicBoolean(true)
    private var masterFd = -1
    private var slaveFd = -1
    private var pid: pid_t = -1
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    init {
        val binName = if (isSimulator()) "mosh-client-sim" else "mosh-client-device"
        val workDir = setupRuntime(binName)
        val binPath = "$workDir/$binName"
        // mosh-client 只接受数字 IP（AI_NUMERICHOST），域名必须先解析
        val resolvedIp = resolveHost(ip)

        memScoped {
            val fdsC = allocArray<IntVar>(2)
            if (mssh_openpty(fdsC, rows, columns) != 0) {
                throw SshException("openpty 失败")
            }
            masterFd = fdsC[0]
            slaveFd = fdsC[1]

            val argStrs = listOf(binPath, resolvedIp, port.toString())
            val argv = allocArray<CPointerVar<ByteVar>>(argStrs.size + 1)
            argStrs.forEachIndexed { i, s -> argv[i] = s.cstr.ptr }

            val envStrs = listOf(
                "MOSH_KEY=$key",
                "TERM=xterm-256color",
                "TERMINFO=$workDir/terminfo",
                "LC_CTYPE=en_US.UTF-8",
                "PATH=/usr/bin:/bin:/usr/sbin:/sbin",
            )
            val envp = allocArray<CPointerVar<ByteVar>>(envStrs.size + 1)
            envStrs.forEachIndexed { i, s -> envp[i] = s.cstr.ptr }

            val spawned = mssh_spawn(binPath, argv, envp, masterFd, slaveFd)
            if (spawned <= 0) {
                mssh_close(masterFd, slaveFd)
                masterFd = -1
                slaveFd = -1
                throw SshException("posix_spawn mosh-client 失败 (pid=$spawned)")
            }
            pid = spawned
        }

        scope.launch {
            val buf = ByteArray(64 * 1024)
            try {
                while (active.load()) {
                    val n = buf.usePinned { pinned ->
                        mssh_read(masterFd, pinned.addressOf(0), buf.size.toULong())
                    }
                    if (n <= 0L) break
                    onOutput(buf.copyOf(n.toInt()))
                }
            } catch (_: Exception) {
            } finally {
                if (active.compareAndSet(expectedValue = true, newValue = false)) onExit()
            }
        }
    }

    /** 解析主机名为数字 IP（IPv4）；失败时原样返回交给 mosh-client 报错。 */
    private fun resolveHost(host: String): String {
        if (host.matches(Regex("^(\\d{1,3}\\.){3}\\d{1,3}$")) || host.contains(':')) return host
        return memScoped {
            val hints = alloc<addrinfo>()
            hints.ai_family = AF_INET
            hints.ai_socktype = SOCK_DGRAM
            val res = alloc<CPointerVar<addrinfo>>()
            val gai = getaddrinfo(host, null, hints.ptr, res.ptr)
            if (gai != 0) return@memScoped host
            try {
                val info = res.value ?: return@memScoped host
                // s_addr 按网络字节序存储：内存低地址即 IP 第一个八位组
                val sAddr = info.pointed.ai_addr
                    ?.reinterpret<sockaddr_in>()?.pointed?.sin_addr?.s_addr
                val octets = (0..3).map { ((sAddr?.toInt() ?: 0) ushr (it * 8)) and 0xff }
                octets.joinToString(".")
            } finally {
                freeaddrinfo(res.value)
            }
        }
    }

    /** 把 bundle 里的 mosh-client 与 terminfo 复制到 tmp，并保证可执行。 */
    private fun setupRuntime(binName: String): String {
        val base = NSTemporaryDirectory() ?: throw SshException("无临时目录")
        val dir = "$base/mssh-${ip}-$port"
        NSFileManager.defaultManager.createDirectoryAtPath(
            dir, withIntermediateDirectories = true, attributes = null, error = null,
        )
        val src = NSBundle.mainBundle.URLForResource(binName, withExtension = null)?.path
            ?: throw SshException("bundle 中缺少 $binName")
        val dst = "$dir/$binName"
        // 二进制随版本变化：每次连接都重拷，避免 App 升级后仍用临时目录里的旧文件
        val fm = NSFileManager.defaultManager
        if (fm.fileExistsAtPath(dst)) {
            fm.removeItemAtPath(dst, error = null)
        }
        if (!fm.copyItemAtPath(src, toPath = dst, error = null)) {
            throw SshException("复制 $binName 到临时目录失败")
        }
        chmod(dst, 0x1EDu) // 0755

        val terminfoRoot = "$dir/terminfo"
        val entries = listOf("x/xterm-256color", "x/xterm", "s/screen", "v/vt100")
        for (path in entries) {
            val out = "$terminfoRoot/$path"
            if (NSFileManager.defaultManager.fileExistsAtPath(out)) continue
            try {
                val bytes = runBlocking { Res.readBytes("files/terminfo/$path") }
                val sub = out.substringBeforeLast('/')
                NSFileManager.defaultManager.createDirectoryAtPath(
                    sub, withIntermediateDirectories = true, attributes = null, error = null,
                )
                writeFileBytes(out, bytes)
            } catch (_: Exception) {
            }
        }
        return dir
    }

    private fun writeFileBytes(path: String, bytes: ByteArray) {
        val fd = open(path, O_CREAT or O_TRUNC or O_RDWR, 0x1A4u) // 0644
        if (fd < 0) return
        try {
            var off = 0
            while (off < bytes.size) {
                val n = bytes.usePinned { pinned ->
                    write(fd, pinned.addressOf(off), (bytes.size - off).toULong())
                }
                if (n <= 0L) break
                off += n.toInt()
            }
        } finally {
            close(fd)
        }
    }

    /** 模拟器运行时由系统注入 SIMULATOR_DEVICE_NAME 环境变量。 */
    private fun isSimulator(): Boolean =
        NSProcessInfo.processInfo.environment["SIMULATOR_DEVICE_NAME"] != null

    override fun isActive(): Boolean = active.load()

    override fun resize(columns: Int, rows: Int) {
        if (masterFd >= 0) mssh_resize(masterFd, rows, columns)
    }

    override fun sendData(data: ByteArray) {
        try {
            if (masterFd < 0 || data.isEmpty()) return
            data.usePinned { pinned ->
                mssh_write(masterFd, pinned.addressOf(0), data.size.toULong())
            }
        } catch (_: Exception) {
        }
    }

    override fun close() {
        if (!active.compareAndSet(expectedValue = true, newValue = false)) return
        if (pid > 0) {
            try {
                kill(pid, SIGTERM)
            } catch (_: Exception) {
            }
            try {
                waitpid(pid, null, 0)
            } catch (_: Exception) {
            }
        }
        if (masterFd >= 0 || slaveFd >= 0) {
            mssh_close(masterFd, slaveFd)
            masterFd = -1
            slaveFd = -1
        }
        onExit()
    }
}

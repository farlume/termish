@file:OptIn(ExperimentalForeignApi::class)

package dev.termish.ssh

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.toKString
import libssh2.LIBSSH2_ERROR_EAGAIN
import libssh2.LIBSSH2_FXF_CREAT
import libssh2.LIBSSH2_FXF_READ
import libssh2.LIBSSH2_FXF_TRUNC
import libssh2.LIBSSH2_FXF_WRITE
import libssh2.LIBSSH2_SFTP
import libssh2.LIBSSH2_SFTP_ATTRIBUTES
import libssh2.LIBSSH2_SFTP_HANDLE
import libssh2.LIBSSH2_SFTP_S_IFDIR
import libssh2.LIBSSH2_SFTP_S_IFMT
import libssh2.LIBSSH2_SFTP_STAT
import libssh2.LIBSSH2_SFTP_OPENDIR
import libssh2.LIBSSH2_SFTP_REALPATH
import libssh2.libssh2_sftp_init
import libssh2.libssh2_sftp_close_handle
import libssh2.libssh2_sftp_fstat_ex
import libssh2.libssh2_sftp_mkdir_ex
import libssh2.libssh2_sftp_open_ex
import libssh2.libssh2_sftp_read
import libssh2.libssh2_sftp_readdir_ex
import libssh2.libssh2_sftp_stat_ex
import libssh2.libssh2_sftp_symlink_ex
import libssh2.libssh2_sftp_shutdown
import libssh2.libssh2_sftp_write
import platform.posix.usleep
import sftp_write.termish_sftp_write

actual fun createSftpSession(connection: SshConnection, callbacks: SshCallbacks): SftpSession =
    SftpSessionLibssh2(connection, callbacks)

/**
 * libssh2 SFTP 实现（iOS）：复用 [SshSessionLibssh2] 的认证链路，
 * 阻塞模式调用 libssh2_sftp_*。
 */
private class SftpSessionLibssh2(
    private val connection: SshConnection,
    private val callbacks: SshCallbacks,
) : SftpSession {
    private val ssh = SshSessionLibssh2(connection, callbacks)
    private var sftp: CPointer<LIBSSH2_SFTP>? = null

    private fun sftpOrThrow(): CPointer<LIBSSH2_SFTP> =
        sftp ?: ssh.openSftp()?.also { sftp = it }
        ?: throw SshException("SFTP 通道打开失败")

    override fun list(path: String): List<SftpEntry> {
        val s = sftpOrThrow()
        // cinterop String 参数按 UTF-8 编码：长度必须传字节数（.length 是字符数，
        // 中文文件名/路径会被截断——目录打不开、下载/删除/新建全失败）
        val pathLen = path.encodeToByteArray().size
        val handle = memScoped {
            var h: CPointer<LIBSSH2_SFTP_HANDLE>? = null
            var guard = 0
            while (h == null && guard++ < 200) {
                h = libssh2_sftp_open_ex(s, path, pathLen.toUInt(), 0uL, 0L, LIBSSH2_SFTP_OPENDIR)
                if (h == null) usleep(30_000u)
            }
            h ?: throw SshException("SFTP 打开目录失败: $path")
        }
        return try {
            val entries = ArrayList<SftpEntry>()
            memScoped {
                val buf = allocArray<ByteVar>(4096)
                val longBuf = allocArray<ByteVar>(512)
                while (true) {
                    val n = libssh2_sftp_readdir_ex(
                        handle, buf, 4096uL, longBuf, 512uL, null,
                    )
                    when {
                        n > 0 -> {
                            val name = buf.readBytes(n.toInt()).decodeToString()
                            // libssh2 的 attrs 结构在 cinterop 中不透明，
                            // 从 longentry（drwxr-xr-x 2 root root 4096 Jan 1 12:34 name）解析；
                            // 服务器不返回 longentry（Windows OpenSSH、部分嵌入式 SFTP）时
                            // 类型未知，用 stat 兜底——递归下载目录依赖准确的 isDirectory
                            val long = longBuf.toKString()
                            val entry = parseLongEntry(name, long) ?: statEntry(path, name)
                            entries.add(entry)
                        }
                        n == 0 -> return@memScoped
                        n.toInt() == LIBSSH2_ERROR_EAGAIN -> usleep(30_000u)
                        else -> throw SshException("SFTP 读取目录失败: $path")
                    }
                }
            }
            entries
        } finally {
            libssh2_sftp_close_handle(handle)
        }
    }

    /**
     * 解析 readdir 的 longentry（权限/大小），时间降级为空（Android/desktop 有完整属性）。
     * 类型字符无法识别（longentry 缺失/格式异常）时返回 null，由 [statEntry] 兜底。
     */
    private fun parseLongEntry(name: String, long: String): SftpEntry? {
        // longentry 格式：perm links owner group size date… name（OpenSSH 实测）。
        // size 取正数第 5 列：尾部列数随名字含空格/日期格式浮动（实测
        // "带 空格 的 文件.txt" 会把倒数第 5 列错位到 Aug/12:28），头部固定。
        // 拿不到有效数字退 0（展示性字段，不影响 isDirectory 判定）
        val parts = long.trim().split(Regex("\\s+"))
        val perm = parts.firstOrNull() ?: return null
        if (perm.isEmpty() || perm.first() !in "dl-") return null
        val isDir = perm.startsWith("d")
        val size = parts.getOrNull(4)?.toLongOrNull() ?: 0L
        return SftpEntry(
            name = name,
            isDirectory = isDir,
            permissions = perm,
            size = size,
            modifiedAt = 0L,
            isHidden = name.startsWith("."),
        )
    }

    /** longentry 缺失时的类型兜底：stat 拿真实类型（目录/文件/链接），并带上大小/时间。 */
    private fun statEntry(dirPath: String, name: String): SftpEntry {
        val s = sftpOrThrow()
        val full = if (dirPath == "/") "/$name" else "$dirPath/$name"
        val fullLen = full.encodeToByteArray().size
        memScoped {
            val attrs = alloc<LIBSSH2_SFTP_ATTRIBUTES>()
            var guard = 0
            while (true) {
                val rc = libssh2_sftp_stat_ex(s, full, fullLen.toUInt(), LIBSSH2_SFTP_STAT, attrs.ptr)
                if (rc == 0) {
                    val perm = attrs.permissions
                    return SftpEntry(
                        name = name,
                        isDirectory = perm and LIBSSH2_SFTP_S_IFDIR.toULong() != 0uL,
                        permissions = formatPerm(perm),
                        size = attrs.filesize.toLong(),
                        modifiedAt = attrs.mtime.toLong() * 1000L,
                        isHidden = name.startsWith("."),
                    )
                }
                if (rc == LIBSSH2_ERROR_EAGAIN && guard++ < 200) {
                    usleep(30_000u)
                    continue
                }
                // stat 失败：按普通文件处理（下载/进入时自然会再报错），不中断整个列表
                return SftpEntry(
                    name = name, isDirectory = false, permissions = "-rw-r--r--",
                    size = 0L, modifiedAt = 0L, isHidden = name.startsWith("."),
                )
            }
        }
    }

    /** 数字 mode → "drwxr-xr-x" 权限串（stat 兜底路径用，展示用途）。 */
    private fun formatPerm(mode: ULong): String {
        val type = if (mode and LIBSSH2_SFTP_S_IFDIR.toULong() != 0uL) 'd' else '-'
        val chars = "rwxrwxrwx"
        val bits = longArrayOf(0x100, 0x80, 0x40, 0x20, 0x10, 0x8, 0x4, 0x2, 0x1)
        return buildString {
            append(type)
            for (i in 0..8) append(if (mode and bits[i].toULong() != 0uL) chars[i] else '-')
        }
    }

    override fun mkdir(path: String) {
        val s = sftpOrThrow()
        if (libssh2_sftp_mkdir_ex(s, path, path.encodeToByteArray().size.toUInt(), 0x1EDL) != 0) {
            throw SshException("SFTP 创建目录失败: $path")
        }
    }

    override fun home(): String {
        val s = sftpOrThrow()
        memScoped {
            val buf = allocArray<ByteVar>(4096)
            while (true) {
                val n = libssh2_sftp_symlink_ex(s, ".", 1u, buf, 4096u, LIBSSH2_SFTP_REALPATH)
                if (n >= 0) {
                    val path = buf.readBytes(n).decodeToString()
                        .trimEnd('\u0000')
                        .trimEnd('/')
                        .ifEmpty { "/" }
                    return path
                }
                if (n.toInt() == LIBSSH2_ERROR_EAGAIN) {
                    usleep(30_000u)
                    continue
                }
                throw SshException("SFTP 获取主目录失败")
            }
        }
    }

    override fun upload(
        remotePath: String,
        totalSize: Long,
        onProgress: (sent: Long, total: Long) -> Unit,
        nextChunk: () -> ByteArray?,
    ) {
        val s = sftpOrThrow()
        val flags = (LIBSSH2_FXF_WRITE or LIBSSH2_FXF_CREAT or LIBSSH2_FXF_TRUNC).toULong()
        val h = libssh2_sftp_open_ex(s, remotePath, remotePath.encodeToByteArray().size.toUInt(), flags, 0x1A4L, 0)
            ?: throw SshException("SFTP 打开文件失败: $remotePath")
        try {
            // 拉取分块逐块写：内存峰值 = 单块大小，与 download 对称
            var sent = 0L
            while (true) {
                // 防御：声明了 totalSize 的源不得超发（同 JVM 实现）
                if (totalSize > 0 && sent >= totalSize) break
                val chunk = nextChunk() ?: break
                chunk.usePinned { pinned ->
                    var written = 0
                    while (written < chunk.size) {
                        var guard = 0
                        while (true) {
                            val n = termish_sftp_write(
                                h, pinned.addressOf(written).reinterpret(), (chunk.size - written).toULong(),
                            )
                            if (n > 0) {
                                written += n.toInt()
                                break
                            }
                            if (n.toInt() == LIBSSH2_ERROR_EAGAIN && guard++ < 200) {
                                usleep(30_000u)
                                continue
                            }
                            throw SshException("SFTP 写入失败: $remotePath")
                        }
                    }
                }
                sent += chunk.size
                onProgress(sent, totalSize)
            }
        } finally {
            libssh2_sftp_close_handle(h)
        }
    }

    override fun download(
        remotePath: String,
        onProgress: (loaded: Long, total: Long) -> Unit,
        onChunk: (ByteArray) -> Unit,
    ) {
        val s = sftpOrThrow()
        val h = libssh2_sftp_open_ex(s, remotePath, remotePath.encodeToByteArray().size.toUInt(), LIBSSH2_FXF_READ.toULong(), 0L, 0)
            ?: throw SshException("SFTP 打开文件失败: $remotePath")
        try {
            val total = fileSize(h)
            memScoped {
                val buf = allocArray<ByteVar>(64 * 1024)
                var loaded = 0L
                while (true) {
                    val n = libssh2_sftp_read(h, buf, (64 * 1024).toULong())
                    when {
                        n > 0 -> {
                            onChunk(buf.readBytes(n.toInt()))
                            loaded += n
                            onProgress(loaded, total)
                        }
                        n == 0L -> return
                        n.toInt() == LIBSSH2_ERROR_EAGAIN -> usleep(30_000u)
                        else -> throw SshException("SFTP 读取失败: $remotePath")
                    }
                }
            }
        } finally {
            libssh2_sftp_close_handle(h)
        }
    }

    /** 已打开文件句柄的大小（fstat）；失败/未知返回 0（进度 total 不可用）。 */
    private fun fileSize(h: CPointer<LIBSSH2_SFTP_HANDLE>): Long {
        memScoped {
            val attrs = alloc<LIBSSH2_SFTP_ATTRIBUTES>()
            var guard = 0
            while (guard++ < 200) {
                val rc = libssh2_sftp_fstat_ex(h, attrs.ptr, 0)
                if (rc == 0) return attrs.filesize.toLong()
                if (rc != LIBSSH2_ERROR_EAGAIN) break
                usleep(30_000u)
            }
        }
        return 0L
    }

    override fun close() {
        try {
            sftp?.let { libssh2_sftp_shutdown(it) }
        } catch (_: Exception) {
        }
        sftp = null
        ssh.close()
    }

}

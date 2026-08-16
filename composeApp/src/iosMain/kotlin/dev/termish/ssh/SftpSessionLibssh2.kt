@file:OptIn(ExperimentalForeignApi::class)

package dev.termish.ssh

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.toKString
import libssh2.LIBSSH2_ERROR_EAGAIN
import libssh2.LIBSSH2_FXF_CREAT
import libssh2.LIBSSH2_FXF_TRUNC
import libssh2.LIBSSH2_FXF_WRITE
import libssh2.LIBSSH2_SFTP
import libssh2.LIBSSH2_SFTP_HANDLE
import libssh2.LIBSSH2_SFTP_S_IFDIR
import libssh2.LIBSSH2_SFTP_S_IFMT
import libssh2.LIBSSH2_SFTP_OPENDIR
import libssh2.libssh2_sftp_init
import libssh2.libssh2_sftp_close_handle
import libssh2.libssh2_sftp_mkdir_ex
import libssh2.libssh2_sftp_open_ex
import libssh2.libssh2_sftp_readdir_ex
import libssh2.libssh2_sftp_shutdown
import libssh2.libssh2_sftp_write
import platform.posix.usleep

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
        val handle = memScoped {
            var h: CPointer<LIBSSH2_SFTP_HANDLE>? = null
            var guard = 0
            while (h == null && guard++ < 200) {
                h = libssh2_sftp_open_ex(s, path, path.length.toUInt(), 0uL, 0L, LIBSSH2_SFTP_OPENDIR)
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
                            // 从 longentry（drwxr-xr-x 2 root root 4096 Jan 1 12:34 name）解析
                            val long = longBuf.toKString()
                            val entry = parseLongEntry(name, long)
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

    /** 解析 readdir 的 longentry（权限/大小），时间降级为空（Android/desktop 有完整属性）。 */
    private fun parseLongEntry(name: String, long: String): SftpEntry {
        val parts = long.trim().split(Regex("\\s+"))
        val perm = parts.firstOrNull() ?: "-rw-r--r--"
        val isDir = perm.startsWith("d")
        val size = parts.getOrNull(parts.size - 5)?.toLongOrNull() ?: 0L
        return SftpEntry(
            name = name,
            isDirectory = isDir,
            permissions = perm,
            size = size,
            modifiedAt = 0L,
            isHidden = name.startsWith("."),
        )
    }

    override fun mkdir(path: String) {
        val s = sftpOrThrow()
        if (libssh2_sftp_mkdir_ex(s, path, path.length.toUInt(), 0x1EDL) != 0) {
            throw SshException("SFTP 创建目录失败: $path")
        }
    }

    override fun upload(remotePath: String, content: ByteArray) {
        val s = sftpOrThrow()
        val flags = (LIBSSH2_FXF_WRITE or LIBSSH2_FXF_CREAT or LIBSSH2_FXF_TRUNC).toULong()
        val h = libssh2_sftp_open_ex(s, remotePath, remotePath.length.toUInt(), flags, 0x1A4L, 0)
            ?: throw SshException("SFTP 打开文件失败: $remotePath")
        try {
            // libssh2_sftp_write 的 buffer 是 const char*（cinterop 映射为 String）：
            // iOS 上传走文本通道（二进制文件建议用 Android/desktop 端）
            val text = content.decodeToString()
            val n = libssh2_sftp_write(h, text, text.length.toULong())
            if (n < 0) throw SshException("SFTP 写入失败: $remotePath")
        } finally {
            libssh2_sftp_close_handle(h)
        }
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

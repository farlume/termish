package dev.termish.ssh

import java.io.ByteArrayInputStream
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.xfer.LocalFileFilter
import net.schmizz.sshj.xfer.LocalSourceFile
import net.schmizz.sshj.xfer.FilePermission

actual fun createSftpSession(connection: SshConnection, callbacks: SshCallbacks): SftpSession =
    SftpSessionSshj(connection, callbacks)

/**
 * sshj SFTP 实现（Android / desktop）：复用 [SshSessionSshj] 的认证链路
 * （密码 / 私钥 / 加密私钥 / keyboard-interactive / 主机密钥确认），
 * 在独立 SSH 连接上打开 SFTP 通道。
 */
class SftpSessionSshj(
    private val connection: SshConnection,
    private val callbacks: SshCallbacks,
) : SftpSession {
    private val ssh = SshSessionSshj(connection, callbacks)
    private var client: SFTPClient? = null

    private fun clientOrThrow(): SFTPClient =
        client ?: ssh.openSftp().also { client = it }

    override fun list(path: String): List<SftpEntry> {
        val c = clientOrThrow()
        return c.ls(path).map { f ->
            val a = f.attributes
            SftpEntry(
                name = f.name,
                isDirectory = a.type == FileMode.Type.DIRECTORY,
                permissions = permString(a.permissions, a.type == FileMode.Type.DIRECTORY),
                size = a.size,
                modifiedAt = a.mtime * 1000L,
                isHidden = f.name.startsWith("."),
            )
        }
    }

    override fun mkdir(path: String) {
        clientOrThrow().mkdir(path)
    }

    override fun upload(remotePath: String, content: ByteArray) {
        val c = clientOrThrow()
        val source = object : LocalSourceFile {
            override fun getName(): String = remotePath.substringAfterLast('/')
            override fun getLength(): Long = content.size.toLong()
            override fun getInputStream(): java.io.InputStream = ByteArrayInputStream(content)
            override fun getPermissions(): Int = 0x1A4 // 0644
            override fun isFile(): Boolean = true
            override fun isDirectory(): Boolean = false
            override fun getChildren(filter: LocalFileFilter?): Iterable<LocalSourceFile> = emptyList()
            override fun providesAtimeMtime(): Boolean = false
            override fun getLastAccessTime(): Long = 0L
            override fun getLastModifiedTime(): Long = 0L
        }
        c.put(source, remotePath)
    }

    override fun download(remotePath: String, onChunk: (ByteArray) -> Unit) {
        val c = clientOrThrow()
        val remote = c.open(remotePath)
        try {
            val buf = ByteArray(64 * 1024)
            var offset = 0L
            while (true) {
                val n = remote.read(offset, buf, 0, buf.size)
                if (n <= 0) break
                onChunk(buf.copyOf(n))
                offset += n
            }
        } finally {
            remote.close()
        }
    }

    override fun close() {
        try {
            client?.close()
        } catch (_: Exception) {
        }
        client = null
        ssh.close()
    }

    private fun permString(perms: Set<FilePermission>, isDir: Boolean): String {
        val sb = StringBuilder(10)
        sb.append(if (isDir) 'd' else '-')
        val order = listOf(
            FilePermission.USR_R, FilePermission.USR_W, FilePermission.USR_X,
            FilePermission.GRP_R, FilePermission.GRP_W, FilePermission.GRP_X,
            FilePermission.OTH_R, FilePermission.OTH_W, FilePermission.OTH_X,
        )
        for (p in order) sb.append(if (p in perms) permChar(p) else '-')
        return sb.toString()
    }

    private fun permChar(p: FilePermission): Char = when (p) {
        FilePermission.USR_R, FilePermission.GRP_R, FilePermission.OTH_R -> 'r'
        FilePermission.USR_W, FilePermission.GRP_W, FilePermission.OTH_W -> 'w'
        else -> 'x'
    }
}

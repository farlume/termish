package dev.termish.ssh

import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.SFTPClient
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
    // SFTP 是请求-响应式（open/read/write/close 每个都等回包）：30s 无响应必是
    // 断链/异常，限时失败才能被 UI 捕获提示，否则无线挂死（连接被掐后 write 等不到回包）
    private val ssh = SshSessionSshj(connection, callbacks, readTimeoutMs = 30_000L)
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

    override fun delete(path: String) {
        deleteRecursive(clientOrThrow(), path)
    }

    private fun deleteRecursive(c: SFTPClient, path: String) {
        val attrs = c.stat(path)
        if (attrs.type == FileMode.Type.DIRECTORY) {
            c.ls(path).forEach { f ->
                if (f.name == "." || f.name == "..") return@forEach
                deleteRecursive(c, joinRemote(path, f.name))
            }
            c.rmdir(path)
        } else {
            c.rm(path)
        }
    }

    override fun rename(oldPath: String, newPath: String) {
        clientOrThrow().rename(oldPath, newPath)
    }

    override fun home(): String = clientOrThrow().canonicalize(".")

    override fun upload(
        remotePath: String,
        totalSize: Long,
        onProgress: (sent: Long, total: Long) -> Unit,
        nextChunk: () -> ByteArray?,
    ) {
        val c = clientOrThrow()
        // 新建/截断写入（权限走服务器默认 umask，与远端 shell 重定向一致）；
        // 分块推流：内存峰值 = 单块大小，与 download 对称
        val remote = c.open(remotePath, setOf(OpenMode.CREAT, OpenMode.WRITE, OpenMode.TRUNC))
        try {
            val buf = ByteArray(64 * 1024)
            var sent = 0L
            while (true) {
                // 防御：声明了 totalSize 的源不得超发（无限 nextChunk 会把远端磁盘灌爆/连接永不停）
                if (totalSize > 0 && sent >= totalSize) break
                val chunk = nextChunk() ?: break
                var off = 0
                while (off < chunk.size) {
                    val n = minOf(buf.size, chunk.size - off)
                    chunk.copyInto(buf, 0, off, off + n)
                    remote.write(sent, buf, 0, n)
                    off += n
                    sent += n
                    onProgress(sent, totalSize)
                }
            }
        } finally {
            remote.close()
        }
    }

    override fun download(
        remotePath: String,
        onProgress: (loaded: Long, total: Long) -> Unit,
        onChunk: (ByteArray) -> Unit,
    ) {
        val c = clientOrThrow()
        val remote = c.open(remotePath)
        try {
            val total = remote.length()
            val buf = ByteArray(64 * 1024)
            var offset = 0L
            while (true) {
                val n = remote.read(offset, buf, 0, buf.size)
                if (n <= 0) break
                onChunk(buf.copyOf(n))
                offset += n
                onProgress(offset, total)
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

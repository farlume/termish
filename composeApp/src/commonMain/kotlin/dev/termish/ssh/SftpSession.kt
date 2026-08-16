package dev.termish.ssh

/** 目录/文件条目（SFTP 列表用）。 */
data class SftpEntry(
    val name: String,
    val isDirectory: Boolean,
    /** 权限串，如 drwxr-xr-x。 */
    val permissions: String,
    val size: Long,
    /** 修改时间（epoch millis）。 */
    val modifiedAt: Long,
    val isHidden: Boolean,
)

/**
 * 平台无关的 SFTP 会话：独立 SSH 连接 + SFTP 通道（认证/主机密钥确认
 * 复用 [SshCallbacks]，弹窗由 UI 层全局处理）。
 */
interface SftpSession {
    /** 列目录；失败抛异常（由 UI 层提示）。 */
    fun list(path: String): List<SftpEntry>

    /** 创建目录。 */
    fun mkdir(path: String)

    /** 上传字节内容到远端路径。 */
    fun upload(remotePath: String, content: ByteArray)

    /**
     * 下载远端文件到本地：以 64KB 左右的分块回调 [onChunk]（阻塞调用）。
     * 由 UI 层负责把分块写入本地文件并 close 保存通道。
     */
    fun download(remotePath: String, onChunk: (ByteArray) -> Unit)

    fun close()
}

/** 平台工厂：JVM=sshj SFTPClient；iOS=libssh2_sftp。 */
expect fun createSftpSession(connection: SshConnection, callbacks: SshCallbacks): SftpSession

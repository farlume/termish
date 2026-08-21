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

    /**
     * 删除文件或目录；目录非空时**递归删除**（平台实现内部处理，与
     * shell `rm -r` 语义一致）。失败抛异常（如无权限/只读）。
     */
    fun delete(path: String)

    /** 重命名/移动（同目录改名或跨目录 move）。失败抛异常。 */
    fun rename(oldPath: String, newPath: String)

    /**
     * 用户主目录（服务器端 SFTP 工作目录，即 ~）：用 realpath(".") 解析，
     * 比猜 /home/xxx 通用（macOS/BSD/自定义 home 均覆盖）；失败抛异常。
     */
    fun home(): String

    /**
     * 流式上传：反复回调 [nextChunk] 取下一块（返回 null = EOF），逐块写远端，
     * 任意大小文件都不在内存里整体驻留（与 [download] 的分块回调对称）。
     * [totalSize] 用于进度显示（未知传 0）；[onProgress] 回调已传/总字节数。
     */
    fun upload(
        remotePath: String,
        totalSize: Long,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
        nextChunk: () -> ByteArray?,
    )

    /**
     * 下载远端文件到本地：以 64KB 左右的分块回调 [onChunk]（阻塞调用）。
     * [onProgress] 回调已下载/总字节数（total 未知时为 0，如某些服务器不报大小）。
     * 由 UI 层负责把分块写入本地文件并 close 保存通道。
     */
    fun download(
        remotePath: String,
        onProgress: (loaded: Long, total: Long) -> Unit = { _, _ -> },
        onChunk: (ByteArray) -> Unit,
    )

    fun close()
}

/** 平台工厂：JVM=sshj SFTPClient；iOS=libssh2_sftp。 */
expect fun createSftpSession(connection: SshConnection, callbacks: SshCallbacks): SftpSession

/** 远端路径拼接：根目录下不产生双斜杠（平台删除/重命名递归用）。 */
internal fun joinRemote(base: String, name: String): String =
    if (base == "/") "/$name" else "$base/$name"

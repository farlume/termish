package dev.termish.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.termish.ssh.SshCallbacks
import dev.termish.ssh.SshConnection
import dev.termish.ssh.createSftpSession
import dev.termish.util.ioDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 上传会话状态（驱动右下角进度浮层）。 */
sealed interface UploadUiState {
    data object Idle : UploadUiState

    /** 上传中：当前文件进度。 */
    data class Uploading(
        val name: String,
        val sent: Long,
        val total: Long,
        val doneCount: Int,
        val totalCount: Int,
    ) : UploadUiState

    /** 全部完成（paths = 成功上传的远端路径，用于自动输入终端）。 */
    data class Done(
        val count: Int,
        val paths: List<String>,
    ) : UploadUiState

    /** 失败（含原因，UI 提示后回 Idle）。 */
    data class Failed(
        val message: String,
    ) : UploadUiState
}

/**
 * 终端文件上传器：复用已认证连接参数（密码/私钥/主机）新建 SFTP 连接，
 * 与交互会话解耦（mosh 模式同样适用——SFTP 走独立 SSH 连接）。
 * 多选文件进队列**串行**上传（每次一个 SFTP 会话，传完关闭）；
 * 流式上传：本地文件逐块读、逐块写，任意大小不整体驻内存。
 */
class TerminalFileUploader(
    private val controller: TerminalController,
    private val scope: CoroutineScope,
) {
    var state: UploadUiState = UploadUiState.Idle
        private set
    var onState: ((UploadUiState) -> Unit)? = null

    private val queue = ArrayDeque<PickedFile>()
    private var job: Job? = null

    /** 入队并启动（若未在传）串行上传。 */
    fun enqueue(
        file: PickedFile,
        targetDir: String,
    ) {
        if (job?.isActive == true) {
            queue.addLast(file)
            return
        }
        queue.addLast(file)
        job = scope.launch { drain(targetDir) }
    }

    private suspend fun drain(targetDir: String) {
        val total = queue.size
        var done = 0
        val uploadedPaths = mutableListOf<String>()
        while (queue.isNotEmpty()) {
            val f = queue.removeFirst()
            try {
                val path = uploadOne(f, targetDir, done, total)
                uploadedPaths.add(path)
                done++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = UploadUiState.Failed(e.message ?: (e::class.simpleName ?: "error"))
                onState?.invoke(state)
                return
            }
        }
        state = UploadUiState.Done(done, uploadedPaths)
        onState?.invoke(state)
    }

    /**
     * 上传单个文件到远端目录。SFTP 会话每次新建并关闭（与 SftpScreen 一致）。
     */
    private suspend fun uploadOne(
        picked: PickedFile,
        targetDir: String,
        index: Int,
        queueTotal: Int,
    ): String {
        val callbacks =
            object : SshCallbacks {
                override suspend fun onOutput(data: ByteArray) {}

                override suspend fun onStderr(data: ByteArray) {}

                override fun onExitStatus(status: Int) {}

                override fun onClosed(reason: String?) {}

                override suspend fun onPrompt(prompt: dev.termish.ssh.AuthPrompt): List<String>? = null

                override fun verifyHostKey(hostKey: dev.termish.ssh.HostKeyInfo): Boolean = true
            }
        val conn =
            SshConnection(
                host = controller.host.hostname,
                port = controller.host.port,
                username = controller.host.username,
                password = controller.password,
                privateKeyPem = controller.privateKeyPem,
                connectTimeoutMillis = 10_000,
                keepAliveSeconds = 0,
            )
        val sftp = createSftpSession(conn, callbacks)
        try {
            val remotePath = if (targetDir.endsWith("/")) "$targetDir${picked.name}" else "$targetDir/${picked.name}"
            withContext(ioDispatcher()) {
                sftp.upload(
                    remotePath = remotePath,
                    totalSize = picked.size,
                    onProgress = { sent, total ->
                        scope.launch {
                            state = UploadUiState.Uploading(picked.name, sent, total, index, queueTotal)
                            onState?.invoke(state)
                        }
                    },
                    nextChunk = { picked.readChunk() },
                )
            }
            return remotePath
        } finally {
            sftp.close()
        }
    }
}

/** 上传目标目录选项卡片：图标 + 主标题 + 路径副标题 + 右箭头，点按区域明确。 */
@Composable
fun UploadDirOptionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val surface = MaterialTheme.colorScheme.surfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(
                    36.dp,
                ).clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = onSurface,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = muted,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * 传输进度浮层（终端上传 / SFTP 下载共用）：surface 圆角卡片 + 品牌绿进度条。
 * 文件名 + 百分比 + 副标题（如「上传中 1/3」「下载中…」）。
 */
@Composable
fun TransferProgressCard(
    /** 文件名。 */
    title: String,
    /** 进度 0..1（未知总大小传 0，进度条不推进）。 */
    progress: Float,
    /** 百分比文案（如 "42%"；未知留空）。 */
    percent: String,
    /** 副标题（如「上传中 1/3」「下载中…」）。 */
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    val surface = MaterialTheme.colorScheme.surface
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier
            .widthIn(max = 260.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(surface.copy(alpha = 0.97f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                color = onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (percent.isNotBlank()) {
                Text(
                    percent,
                    style = MaterialTheme.typography.labelSmall,
                    color = muted,
                )
            }
        }
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(4.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
        )
        if (subtitle.isNotBlank()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = muted,
            )
        }
    }
}

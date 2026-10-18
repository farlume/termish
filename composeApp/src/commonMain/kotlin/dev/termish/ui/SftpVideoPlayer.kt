package dev.termish.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.termish.ssh.SftpSession

/** 原生播放器按需读取 SFTP 文件；离开页面释放连接，后台暂停，位置由会话保存。 */
@Composable
expect fun SftpVideoPlayer(
    session: SftpSession,
    remotePath: String,
    initialPositionMillis: Long,
    onPositionChanged: (Long) -> Unit,
    onError: () -> Unit,
    modifier: Modifier = Modifier,
)

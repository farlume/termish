package dev.termish.ui

import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import dev.termish.ssh.SftpSession
import dev.termish.util.TermLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
@Composable
actual fun SftpVideoPlayer(
    session: SftpSession,
    remotePath: String,
    initialPositionMillis: Long,
    onPositionChanged: (Long) -> Unit,
    onError: () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val positionChanged = rememberUpdatedState(onPositionChanged)
    val failed = rememberUpdatedState(onError)
    val source = remember(session, remotePath) { SftpVideoSource { session.openRead(remotePath) } }
    val player =
        remember(source) {
            ExoPlayer
                .Builder(context)
                .setLoadControl(
                    DefaultLoadControl
                        .Builder()
                        .setBufferDurationsMs(10_000, 30_000, 1000, 2000)
                        .setTargetBufferBytes(16 * 1024 * 1024)
                        .setPrioritizeTimeOverSizeThresholds(false)
                        .build(),
                ).build()
                .apply {
                    // URI 只给容器识别提供扩展名，实际所有读取由 SFTP 数据源处理。
                    val uri =
                        Uri
                            .Builder()
                            .scheme("sftp-video")
                            .authority("file")
                            .appendPath(remotePath.substringAfterLast('/'))
                            .build()
                    setMediaSource(ProgressiveMediaSource.Factory { SftpVideoDataSource(source) }.createMediaSource(MediaItem.fromUri(uri)))
                    seekTo(initialPositionMillis)
                }
        }
    DisposableEffect(player, owner) {
        val listener =
            object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    TermLog.w("sftp-video") { "Playback failed: ${error.errorCodeName}" }
                    failed.value()
                }
            }
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE) player.pause()
            }
        player.addListener(listener)
        owner.lifecycle.addObserver(observer)
        player.prepare()
        player.playWhenReady = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        onDispose {
            positionChanged.value(player.currentPosition.coerceAtLeast(0))
            owner.lifecycle.removeObserver(observer)
            player.removeListener(listener)
            player.release()
            // 网络收尾可能等待正在执行的读取，绝不占用 UI 线程。
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { source.release() }.onFailure { TermLog.w("sftp-video") { "Reader cleanup failed" } }
            }
        }
    }
    LaunchedEffect(player) {
        while (true) {
            delay(500)
            positionChanged.value(player.currentPosition.coerceAtLeast(0))
        }
    }
    AndroidView(
        factory = {
            PlayerView(it).apply {
                this.player = player
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            }
        },
        modifier = modifier,
        update = { it.player = player },
        onRelease = { it.player = null },
    )
}

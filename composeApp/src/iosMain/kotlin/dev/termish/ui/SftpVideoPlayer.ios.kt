@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class, ExperimentalComposeUiApi::class)

package dev.termish.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropInteractionMode
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitViewController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.termish.ssh.SftpReadHandle
import dev.termish.ssh.SftpSession
import dev.termish.util.TermLog
import kotlin.concurrent.Volatile
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.delay
import platform.AVFoundation.AVAssetResourceLoader
import platform.AVFoundation.AVAssetResourceLoaderDelegateProtocol
import platform.AVFoundation.AVAssetResourceLoadingRequest
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.preferredForwardBufferDuration
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.resourceLoader
import platform.AVFoundation.seekToTime
import platform.AVKit.AVPlayerViewController
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_queue_create

/** AVFoundation 请求的 Range 直接映射到 SFTP offset，不创建 HTTP 服务/临时完整文件。 */
private class SftpVideoLoader(
    private val session: SftpSession,
    private val path: String,
    private val onError: () -> Unit,
) : NSObject(),
    AVAssetResourceLoaderDelegateProtocol {
    val queue = dispatch_queue_create("dev.termish.sftp-video", null)
    private var reader: SftpReadHandle? = null

    @Volatile private var closed = false
    private var failed = false

    override fun resourceLoader(
        resourceLoader: AVAssetResourceLoader,
        shouldWaitForLoadingOfRequestedResource: AVAssetResourceLoadingRequest,
    ): Boolean {
        val request = shouldWaitForLoadingOfRequestedResource
        dispatch_async(queue) { loadChunk(request) }
        return true
    }

    private fun loadChunk(request: AVAssetResourceLoadingRequest) {
        if (closed || request.cancelled || request.finished) return
        try {
            val file = reader ?: session.openRead(path).also { reader = it }
            if (closed || request.cancelled) return
            request.contentInformationRequest?.apply {
                contentLength = file.size
                byteRangeAccessSupported = true
                contentType =
                    when (path.substringAfterLast('.').lowercase()) {
                        "mov" -> "com.apple.quicktime-movie"
                        "m4v" -> "com.apple.m4v-video"
                        else -> "public.mpeg-4"
                    }
            }
            val data = request.dataRequest
            if (data == null) {
                request.finishLoading()
                return
            }
            val offset = maxOf(data.currentOffset, data.requestedOffset)
            val end =
                if (data.requestsAllDataToEndOfResource) {
                    file.size
                } else {
                    minOf(file.size, data.requestedOffset + data.requestedLength.toLong())
                }
            if (offset >= end) {
                request.finishLoading()
                return
            }
            val bytes = file.read(offset, minOf(64L * 1024, end - offset).toInt())
            check(bytes.isNotEmpty()) { "SFTP video changed while playing" }
            if (closed || request.cancelled) return
            data.respondWithData(bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) })
            if (offset + bytes.size >= end) {
                request.finishLoading()
            } else {
                // 每块让出队列，尾部元数据、seek、取消和 close 都不会被长请求阻塞。
                dispatch_async(queue) { loadChunk(request) }
            }
        } catch (e: Exception) {
            request.finishLoadingWithError(NSError.errorWithDomain("dev.termish.sftp-video", -1, null))
            if (!failed && !closed) {
                failed = true
                TermLog.w("sftp-video") { "SFTP resource loading failed" }
                dispatch_async(dispatch_get_main_queue()) { if (!closed) onError() }
            }
        }
    }

    fun close() {
        closed = true
        dispatch_async(queue) {
            runCatching { reader?.close() }.onFailure { TermLog.w("sftp-video") { "Reader cleanup failed" } }
            reader = null
        }
    }
}

@Composable
actual fun SftpVideoPlayer(
    session: SftpSession,
    remotePath: String,
    initialPositionMillis: Long,
    onPositionChanged: (Long) -> Unit,
    onError: () -> Unit,
    modifier: Modifier,
) {
    val owner = LocalLifecycleOwner.current
    val positionChanged = rememberUpdatedState(onPositionChanged)
    val failed = rememberUpdatedState(onError)
    val loader = remember(session, remotePath) { SftpVideoLoader(session, remotePath) { failed.value() } }
    val player =
        remember(loader) {
            // 不把远端地址/凭据嵌入 URL，仅保留扩展名帮助 AVFoundation 识别容器。
            val extension = remotePath.substringAfterLast('.', "mp4").lowercase()
            val asset = AVURLAsset(NSURL(string = "sftp-video://file/video.$extension"), null)
            asset.resourceLoader.setDelegate(loader, loader.queue)
            AVPlayer(playerItem = AVPlayerItem(asset = asset).apply { preferredForwardBufferDuration = 15.0 }).apply {
                seekToTime(CMTimeMakeWithSeconds(initialPositionMillis / 1000.0, 1000))
            }
        }
    val controller =
        remember(player) {
            AVPlayerViewController().apply {
                this.player = player
                showsPlaybackControls = true
                allowsPictureInPicturePlayback = false
            }
        }
    DisposableEffect(player, owner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE) player.pause()
            }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) player.play()
        onDispose {
            val seconds = CMTimeGetSeconds(player.currentTime())
            if (seconds.isFinite()) positionChanged.value((seconds * 1000).toLong().coerceAtLeast(0))
            owner.lifecycle.removeObserver(observer)
            player.pause()
            controller.player = null
            player.replaceCurrentItemWithPlayerItem(null)
            loader.close()
        }
    }
    LaunchedEffect(player) {
        while (true) {
            delay(500)
            if (player.currentItem?.status == AVPlayerItemStatusFailed) {
                TermLog.w("sftp-video") { "AVPlayer failed: ${player.currentItem?.error?.code}" }
                failed.value()
                break
            }
            val seconds = CMTimeGetSeconds(player.currentTime())
            if (seconds.isFinite()) positionChanged.value((seconds * 1000).toLong().coerceAtLeast(0))
        }
    }
    key(controller) {
        UIKitViewController(
            factory = { controller },
            modifier = modifier,
            properties =
                UIKitInteropProperties(
                    interactionMode = UIKitInteropInteractionMode.NonCooperative,
                    isNativeAccessibilityEnabled = true,
                ),
        )
    }
}

package dev.termish.screen

import android.graphics.Color
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.termish.AppContext
import dev.termish.util.TermLog
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Android 实现：NanoHTTPD 本地流服务（127.0.0.1 随机端口，MPEG-TS chunked 流）
 * + ExoPlayer 播放。解码/码流解析/上屏全部交给 ExoPlayer（内部处理各厂商
 * MediaCodec 差异——手写管线在 OPPO/MTK 上吞输入不出帧，实测字节级正确仍零输出）。
 */
actual class ScreenPlayer actual constructor(
    private val onReady: () -> Unit,
    private val onError: (String) -> Unit,
) {
    /**
     * 供 UI 绑定的播放器实例。低延迟 LoadControl：实时推流（本地 HTTP 渐进式
     * MPEG-TS）无需 VOD 式预缓冲——默认 50s minBuffer / 1s 首帧缓冲会把画面
     * 延迟拉高到秒级；压到 200ms 首帧 + 500ms 目标缓冲，做到准实时。
     */
    val player: ExoPlayer =
        ExoPlayer
            .Builder(AppContext.get())
            .setLoadControl(
                DefaultLoadControl
                    .Builder()
                    .setBufferDurationsMs(
                        MIN_BUFFER_MS, // minBuffer：缓冲目标（500ms 是 shouldContinueLoading 硬下限）
                        MAX_BUFFER_MS, // maxBuffer
                        BUFFER_FOR_PLAYBACK_MS, // 首帧前缓冲：越低首帧越快
                        BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS, // 重缓冲后恢复
                    ).setPrioritizeTimeOverSizeThresholds(true)
                    .build(),
            ).build()

    companion object {
        /** 低延迟推流缓冲档位（毫秒）。实时推流无 VOD 预缓冲需求，越小延迟越低。 */
        private const val MIN_BUFFER_MS = 500
        private const val MAX_BUFFER_MS = 600
        private const val BUFFER_FOR_PLAYBACK_MS = 120
        private const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 200

        /** 队列水位上限（包数）：32 包 × 平均 ~8KB ≈ 256KB，720p 下约 0.5~0.7s 画面，
         *  把延迟漂移上限锁在亚秒级。 */
        private const val MAX_QUEUED_PACKETS = 32

        // 直播边追赶：渐进式直播流无法前向 seek（源是字节流，旧数据不保留），
        // 多余缓冲只能变速消化——落后超过 trigger 提速，回落到 release 恢复常速。
        // 缓冲量 = 直播延时主体（ExoPlayer 在 min~maxBuffer 间震荡填冲），
        // 稳态延时 ≈ release + 解码/上屏 ~100-150ms
        private const val LIVE_EDGE_TRIGGER_MS = 550L
        private const val LIVE_EDGE_RELEASE_MS = 300L
        private const val LIVE_EDGE_FAST_SPEED = 1.25f
        private const val LIVE_EDGE_POLL_MS = 500L
    }

    /** 喂给播放器的字节队列（有界 + 水位控制，见 [feed]）。 */
    private val queue = LinkedBlockingQueue<ByteArray>(MAX_QUEUED_PACKETS)

    @Volatile private var stopped = false
    private var server: StreamServer? = null

    /** 主线程 Handler（ExoPlayer 只能主线程访问）。 */
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 直播边追赶定时器：监控缓冲健康度（=落后直播边的毫秒数），变速消化多余缓冲。 */
    private val liveEdgeTicker =
        object : Runnable {
            override fun run() {
                if (stopped) return
                val behind = player.bufferedPosition - player.currentPosition
                val cur = player.playbackParameters.speed
                val want =
                    when {
                        behind > LIVE_EDGE_TRIGGER_MS -> LIVE_EDGE_FAST_SPEED
                        behind < LIVE_EDGE_RELEASE_MS -> 1.0f
                        else -> cur
                    }
                if (want != cur) {
                    player.setPlaybackSpeed(want)
                    TermLog.i("screen") { "live-edge behind=${behind}ms → speed=$want" }
                }
                mainHandler.postDelayed(this, LIVE_EDGE_POLL_MS)
            }
        }

    private inner class StreamServer : NanoHTTPD("127.0.0.1", 0) {
        override fun serve(session: IHTTPSession): Response {
            TermLog.i("screen") { "http request ${session.uri}" }
            return StreamResponse(
                object : InputStream() {
                    private var current: ByteArray? = null
                    private var pos = 0

                    override fun read(): Int {
                        while (!stopped) {
                            val c = current
                            if (c != null && pos < c.size) {
                                return (c[pos++].toInt() and 0xff)
                            }
                            current = null
                            val next = queue.poll(2, TimeUnit.SECONDS) ?: continue
                            current = next
                            pos = 0
                        }
                        return -1
                    }

                    override fun read(
                        b: ByteArray,
                        off: Int,
                        len: Int,
                    ): Int {
                        if (len == 0) return 0
                        var copied = 0
                        while (copied == 0 && !stopped) {
                            val c = current
                            if (c != null && pos < c.size) {
                                val n = minOf(len - copied, c.size - pos)
                                c.copyInto(b, off + copied, pos, pos + n)
                                pos += n
                                copied += n
                            } else {
                                current = null
                                val next = queue.poll(2, TimeUnit.SECONDS) ?: continue
                                current = next
                                pos = 0
                            }
                        }
                        return if (copied > 0) copied else -1
                    }

                    override fun close() {
                    }
                },
            )
        }
    }

    /** chunked 流式响应：无 Content-Length，边读边发（播放器渐进读取）。 */
    private class StreamResponse(
        data: InputStream,
    ) : Response(
            Response.Status.OK,
            "video/mp2t",
            data,
            -1,
        ) {
        init {
            setChunkedTransfer(true)
            addHeader("Cache-Control", "no-cache")
        }
    }

    actual fun start() {
        if (server != null) return
        try {
            val srv = StreamServer()
            srv.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            server = srv
        } catch (e: IOException) {
            TermLog.w("screen") { "http server failed: $e" }
            onError("本地流服务启动失败：${e.message}")
            return
        }
        val port = server!!.listeningPort
        player.addListener(
            object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    TermLog.i("screen") { "player first frame rendered" }
                    onReady()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    TermLog.i("screen") { "player state=$playbackState (1=buffering 2=ready 3=ended)" }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    TermLog.i("screen") { "player playing=$isPlaying" }
                }

                override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                    TermLog.i("screen") { "video size ${videoSize.width}x${videoSize.height}" }
                }

                override fun onPlayerError(error: PlaybackException) {
                    TermLog.w("screen") { "player error: ${error.errorCodeName} ${error.message}" }
                    onError("播放失败：${error.errorCodeName} ${error.message ?: ""}")
                }
            },
        )
        player.setMediaItem(MediaItem.fromUri("http://127.0.0.1:$port/stream.ts"))
        player.prepare()
        player.play()
        mainHandler.post(liveEdgeTicker)
        TermLog.i("screen") { "player started port=$port" }
    }

    actual fun feed(data: ByteArray) {
        if (stopped) return
        // 水位控制：队列满时丢最旧一包再入队。ExoPlayer 解码慢一拍时不背压远端、
        // 不无限堆积——丢旧保新让画面追平最新（丢帧花屏最多到下一个 IDR，
        // 远端 keyint=30 保证 ≤1s 恢复清晰）。
        var dropped = 0
        while (!queue.offer(data)) {
            if (queue.poll() == null) break
            dropped++
        }
        if (dropped > 0) {
            TermLog.w("screen") { "queue overflow: dropped $dropped old packets" }
        }
    }

    actual fun stop() {
        stopped = true
        mainHandler.removeCallbacks(liveEdgeTicker)
        runCatching { player.release() }
        runCatching { server?.stop() }
        server = null
    }
}

@OptIn(UnstableApi::class)
@Composable
actual fun ScreenVideoSurface(
    player: ScreenPlayer?,
    modifier: Modifier,
) {
    val p = player?.player
    if (p == null) {
        Box(modifier.background(ComposeColor.Black))
        return
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setBackgroundColor(Color.BLACK)
                this.player = p
            }
        },
        update = { it.player = p },
        modifier = modifier,
    )
}

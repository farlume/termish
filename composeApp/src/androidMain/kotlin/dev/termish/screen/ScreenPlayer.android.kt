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
    /** 供 UI 绑定的播放器实例。 */
    val player: ExoPlayer = ExoPlayer.Builder(AppContext.get()).build()

    private val queue = LinkedBlockingQueue<ByteArray>(256)

    @Volatile private var stopped = false
    private var server: StreamServer? = null

    private inner class StreamServer : NanoHTTPD("127.0.0.1", 0) {
        override fun serve(session: IHTTPSession): Response =
            StreamResponse(
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

                override fun onPlayerError(error: PlaybackException) {
                    TermLog.w("screen") { "player error: ${error.errorCodeName} ${error.message}" }
                    onError("播放失败：${error.errorCodeName} ${error.message ?: ""}")
                }
            },
        )
        player.setMediaItem(MediaItem.fromUri("http://127.0.0.1:$port/stream.ts"))
        player.prepare()
        player.play()
        TermLog.i("screen") { "player started port=$port" }
    }

    actual fun feed(data: ByteArray) {
        if (stopped) return
        try {
            queue.put(data)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    actual fun stop() {
        stopped = true
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

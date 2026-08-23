package dev.termish.screen

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.viewinterop.AndroidView
import dev.termish.util.TermLog
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Android 实现：UDP 重组出的完整 H.264 帧（Annex-B，AUD 开头）直接喂
 * MediaCodec 硬解，解码输出即刻上屏——无 MPEG-TS 容器、无本地 HTTP、
 * 无 ExoPlayer 渐进式缓冲垫（那三者叠加出 ~0.4-1s 延时；ToDesk 式直通
 * 管线把延时压到网络 + 解码 + 一次 vsync）。
 *
 * relay 侧已按 AUD 帧对齐（一个 UDP 重组块 = 一个完整帧），丢一片只丢
 * 一帧，下一个 IDR 自动恢复——不再有 TS 流打洞问题。
 *
 * ⚠️ 历史教训：手写 MediaCodec 管线曾在 OPPO/MTK 上吞输入不出帧（当时
 * 从 TS 流切 NAL 喂）。本版规避：CSD 显式 configure（SPS/PPS 从关键帧块
 * 提取）+ 从 IDR 起播 + 独立线程同步模式 + 吞输入 watchdog 打点。
 */
actual class ScreenPlayer actual constructor(
    private val onReady: () -> Unit,
    private val onError: (String) -> Unit,
) {
    val decoder = ScreenDecoder(onReady, onError)

    actual fun start() {
        decoder.start()
    }

    actual fun feed(data: ByteArray) {
        decoder.feedFrame(data)
    }

    actual fun stop() {
        decoder.stop()
    }
}

/**
 * MediaCodec H.264 直解器（独立解码线程 + 有界帧队列）。
 *
 * 状态机（全部 codec 操作都在解码线程，UI 只读写线程安全字段）：
 * 1. [start] 起线程；等待 surface（[attachSurface]，SurfaceView 回调注入）
 * 2. 丢弃队列里的非关键帧，直到第一个含 SPS/PPS 的关键帧 → configure → 起播
 * 3. 每帧一个 input buffer，输出立即 render（到达即上屏）
 * 4. SPS 变化（分辨率切换）→ 重配；surface 销毁 → 释放，下一个关键帧重建
 *
 * 队列水位：解码跟不上（120fps 灌 60fps 解码）丢最旧保新——延迟锁死在
 * 2-3 帧；丢参考帧花屏到下一个 IDR（keyint=30 ≤ 0.5s）自动恢复。
 */
class ScreenDecoder(
    private val onReady: () -> Unit,
    private val onError: (String) -> Unit,
) {
    companion object {
        private const val MIME = "video/avc"
        private const val QUEUE_CAPACITY = 4
        private const val TIMEOUT_US = 10_000L
        private const val PTS_STEP_US = 1_000_000L
    }

    private val queue = LinkedBlockingQueue<ByteArray>(QUEUE_CAPACITY)
    private val surfaceRef = AtomicReference<Surface?>(null)

    /** 视频实际尺寸（裁剪后；从解码器输出 format 上报，UI 按 aspectRatio 布局）。 */
    val videoDims: MutableState<Pair<Int, Int>?> = mutableStateOf(null)

    @Volatile private var running = false

    @Volatile private var firstFrameRendered = false
    private var thread: Thread? = null

    fun attachSurface(surface: Surface) {
        surfaceRef.set(surface)
    }

    fun detachSurface() {
        surfaceRef.set(null)
    }

    fun start() {
        if (running) return
        running = true
        thread =
            Thread({ decodeLoop() }, "screen-decoder").apply {
                priority = Thread.MAX_PRIORITY - 1
                start()
            }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    /** 喂入一个完整帧（Annex-B 字节；UDP 重组层保证帧对齐）。 */
    fun feedFrame(frame: ByteArray) {
        if (!running) return
        // 丢旧保新：水位满时丢最旧一帧（不背压发送端——延迟优先于连续性）
        while (!queue.offer(frame)) {
            if (queue.poll() == null) break
        }
    }

    private fun decodeLoop() {
        var codec: MediaCodec? = null
        var lastDims: Pair<Int, Int>? = null
        var pts = 0L
        var fed = 0
        var dropEvery = 1 // 抽帧：1 = 不抽；n = 每 n 帧丢 1（解码器满时自适应升高）
        var dropCounter = 0

        fun releaseCodec() {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            try {
                codec?.release()
            } catch (_: Exception) {
            }
            codec = null
        }

        try {
            while (running) {
                // surface 生命周期：销毁 → 释放 codec（SurfaceView 回调只置空引用，
                // codec 操作收口在本线程避免并发崩溃）
                if (codec != null && surfaceRef.get() == null) {
                    releaseCodec()
                }

                val frame = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                val ps = H264Stream.extractParameterSets(frame)
                val dims = ps.sps?.let { H264Stream.parseSpsDimensions(it) }

                if (ps.sps != null && ps.pps != null) {
                    // 带完整参数集的关键帧：首次 configure / 分辨率变化重配
                    if (codec != null && dims != null && dims != lastDims) {
                        TermLog.i("screen") { "SPS 变化 $lastDims → $dims，重配解码器" }
                        releaseCodec()
                    }
                    if (codec == null) {
                        val surface = surfaceRef.get() ?: continue // 等 UI surface
                        val fmt =
                            MediaFormat.createVideoFormat(MIME, dims?.first ?: 1920, dims?.second ?: 1080).apply {
                                setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(ps.sps))
                                setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(ps.pps))
                            }
                        runCatching {
                            codec =
                                MediaCodec.createDecoderByType(MIME).also {
                                    it.configure(fmt, surface, null, 0)
                                    it.start()
                                }
                            lastDims = dims
                            fed = 0
                            TermLog.i("screen") { "decoder configured ${dims?.first}x${dims?.second}" }
                        }.onFailure { e ->
                            TermLog.w("screen") { "decoder configure failed: $e" }
                            onError("解码器初始化失败：${e.message}")
                            running = false
                            return
                        }
                    }
                } else if (codec == null) {
                    continue // 起播前：丢弃不含参数集的帧（等关键帧）
                }

                val c = codec ?: continue
                fed++
                // 自适应抽帧：输入超解码极限时按比例抽帧（保持节奏均匀）。
                // 队列丢帧是「丢旧保新」节奏抖动（实测 60fps 流 MTK 解 35fps →
                // 一快一慢）；抽帧在喂入前均匀丢弃，节奏平滑。
                // 输入忙（拿不到 buffer）→ 抽帧率升；连续顺畅 → 缓慢回落
                val idx = c.dequeueInputBuffer(TIMEOUT_US)
                if (idx < 0) {
                    // 解码器满：本帧丢弃 + 提高抽帧强度（下 n 帧丢 1）
                    dropEvery = (dropEvery + 1).coerceAtMost(4)
                    dropCounter = 0
                    renderOutputs(c)
                    continue
                }
                if (dropEvery > 1) {
                    dropCounter++
                    if (dropCounter % dropEvery != 0) {
                        // 被抽掉的帧：占位释放 input buffer（不喂数据）
                        c.queueInputBuffer(idx, 0, 0, pts, 0)
                        renderOutputs(c)
                        continue
                    }
                    if (dropCounter >= dropEvery * 8) dropCounter = 0
                    // 连续 8 组顺畅：抽帧强度回落
                    if (dropCounter == 0 && dropEvery > 1) dropEvery--
                }
                val buf = c.getInputBuffer(idx)
                if (buf != null && frame.size <= buf.capacity()) {
                    buf.clear()
                    buf.put(frame)
                    c.queueInputBuffer(idx, 0, frame.size, pts, 0)
                    pts += PTS_STEP_US
                } else {
                    c.queueInputBuffer(idx, 0, 0, pts, 0)
                }
                renderOutputs(c)

                // ⚠️ MTK 吞输入 watchdog：喂了 600 帧仍零输出 → 上报（历史 OPPO/MTK 坑）
                if (!firstFrameRendered && fed > 600) {
                    TermLog.w("screen") { "解码器吞输入：fed=$fed 无输出" }
                    onError("解码器无输出（fed=$fed）")
                    running = false
                    break
                }
            }
        } catch (e: InterruptedException) {
            // stop() 中断：正常退出
        } catch (e: Exception) {
            TermLog.w("screen") { "decode loop error: ${e::class.simpleName} ${e.message}" }
            onError("解码失败：${e.message ?: e::class.simpleName}")
        } finally {
            releaseCodec()
        }
    }

    private fun renderOutputs(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, 0)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    TermLog.i("screen") { "decoder format: $f" }
                    val dims =
                        runCatching {
                            val w =
                                if (f.containsKey("crop-right") && f.containsKey("crop-left")) {
                                    f.getInteger("crop-right") + 1 - f.getInteger("crop-left")
                                } else {
                                    f.getInteger(MediaFormat.KEY_WIDTH)
                                }
                            val h =
                                if (f.containsKey("crop-bottom") && f.containsKey("crop-top")) {
                                    f.getInteger("crop-bottom") + 1 - f.getInteger("crop-top")
                                } else {
                                    f.getInteger(MediaFormat.KEY_HEIGHT)
                                }
                            w to h
                        }.getOrNull()
                    if (dims != null) videoDims.value = dims
                }
                idx >= 0 -> {
                    if (info.size > 0) {
                        codec.releaseOutputBuffer(idx, true)
                        if (!firstFrameRendered) {
                            firstFrameRendered = true
                            TermLog.i("screen") { "first frame rendered (direct)" }
                            onReady()
                        }
                    } else {
                        codec.releaseOutputBuffer(idx, false)
                    }
                }
            }
        }
    }
}

@Composable
actual fun ScreenVideoSurface(
    player: ScreenPlayer?,
    modifier: Modifier,
) {
    if (player == null) {
        Box(modifier.background(ComposeColor.Black))
        return
    }
    // 宽高比适配：解码器上报实际尺寸前铺满（黑底），上报后按比例居中
    // （替代 ExoPlayer PlayerView 的 RESIZE_MODE_FIT，SurfaceView 不会自己 letterbox）
    val dims = player.decoder.videoDims.value
    Box(modifier.background(ComposeColor.Black), contentAlignment = Alignment.Center) {
        val surfaceModifier =
            if (dims != null) {
                Modifier.aspectRatio(dims.first.toFloat() / dims.second.toFloat())
            } else {
                Modifier
            }
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(
                        object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                player.decoder.attachSurface(holder.surface)
                            }

                            override fun surfaceChanged(
                                holder: SurfaceHolder,
                                format: Int,
                                width: Int,
                                height: Int,
                            ) {
                            }

                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                player.decoder.detachSurface()
                            }
                        },
                    )
                }
            },
            modifier = surfaceModifier,
        )
    }
}

package dev.termish.screen

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** 解码能力探测缓存（分辨率 → 帧率上限），静态复用免每会话重查。 */
private val decoderProbeCache = HashMap<String, Int>()

/** H.264 解码 MIME（探测与解码共用）。 */
private const val DECODER_MIME = "video/avc"
private const val ANDROID_SOFTWARE_AVC_DECODER = "c2.android.avc.decoder"

/**
 * Android 实现：TCP 显式分帧得到的完整 H.264 帧（Annex-B，AUD 开头）直接喂
 * MediaCodec 硬解，解码输出即刻上屏——无 MPEG-TS 容器、无本地 HTTP、
 * 无 ExoPlayer 渐进式缓冲垫（那三者叠加出 ~0.4-1s 延时；ToDesk 式直通
 * 管线把延时压到网络 + 解码 + 一次 vsync）。
 *
 * relay 侧已按 AUD 帧对齐，TCP 长度头保留帧边界；下一个 IDR 可自动
 * 恢复解码同步，不再有 TS 容器缓冲。
 *
 * ⚠️ 历史教训：手写 MediaCodec 管线曾在 OPPO/MTK 上吞输入不出帧（当时
 * 从 TS 流切 NAL 喂）。本版规避：CSD 显式 configure（SPS/PPS 从关键帧块
 * 提取）+ 从 IDR 起播 + 独立线程同步模式 + 吞输入 watchdog 打点。
 */
actual class ScreenPlayer actual constructor(
    private val onReady: () -> Unit,
    private val onError: (ScreenPlayerFailure) -> Unit,
) {
    val decoder = ScreenDecoder(onReady, onError)

    /** 视频实际尺寸（解码器上报）：UI 宽高比布局 + 远程操作坐标映射。 */
    actual val videoDims: MutableState<Pair<Int, Int>?> = decoder.videoDims

    actual val lastRenderedAtMillis: Long
        get() = decoder.lastRenderedAtMillis

    actual val renderSurfaceAttached: Boolean
        get() = decoder.hasSurface()

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
    private val onError: (ScreenPlayerFailure) -> Unit,
) {
    companion object {
        private const val MIME = "video/avc"
        private const val QUEUE_CAPACITY = 4
        private const val TIMEOUT_US = 10_000L

        // 帧间隔 33.3ms（30fps 语义）：PTS 与真实帧率一致，避免个别解码器
        // 按 PTS 排队调度输出（历史值 1s 步进是隐患，Surface 直渲掩盖了它）
        private const val PTS_STEP_US = 33_333L
    }

    private val queue = LinkedBlockingQueue<ByteArray>(QUEUE_CAPACITY)

    /** 渲染帧计数（每秒诊断打点后清零）。 */
    @Volatile private var statRendered = 0

    /** 视频实际尺寸（裁剪后；从解码器输出 format 上报，UI 按 aspectRatio 布局）。 */
    val videoDims: MutableState<Pair<Int, Int>?> = mutableStateOf(null)

    @Volatile private var running = false

    @Volatile private var firstFrameRendered = false

    @Volatile var lastRenderedAtMillis = 0L
        private set
    private var thread: Thread? = null

    /**
     * 当前存活 surface（小窗 + 全屏是两个独立 SurfaceView，可能同时存在：
     * 全屏收起动画期间、全屏覆盖在小窗之上等）。用列表管理而不是单值——
     * 否则全屏 surfaceDestroyed 会把小窗的引用一起清掉，解码器重建后拿
     * 不到 surface，画面停在最后一帧（用户反馈：小窗画面静止）。
     */
    private val surfaces = CopyOnWriteArrayList<Surface>()

    fun attachSurface(surface: Surface) {
        if (surface.isValid && !surfaces.contains(surface)) surfaces.add(surface)
    }

    /** 只移除指定 surface（小窗/全屏各自销毁互不影响）。 */
    fun detachSurface(surface: Surface) {
        surfaces.remove(surface)
    }

    fun hasSurface(): Boolean = surfaces.any { it.isValid }

    private fun currentSurface(): Surface? {
        // Surface 可能已 obsolete，而 surfaceDestroyed 回调还未从 UI 线程到达。
        // 先剔除失效引用，避免 MediaCodec 继续渲染到已销毁的 SurfaceView。
        surfaces.removeAll { !it.isValid }
        return surfaces.lastOrNull()
    }

    private fun isSurfaceFailure(
        surface: Surface?,
        error: Exception,
    ): Boolean {
        val detail = error.message.orEmpty()
        return surface?.isValid != true ||
            detail.contains("obsolete", ignoreCase = true) ||
            detail.contains("non-initialized", ignoreCase = true) ||
            detail.contains("abandoned", ignoreCase = true)
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

    /** 喂入一个完整帧（Annex-B 字节；TCP 协议层保证帧对齐）。 */
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
        // 当前 codec 绑定的输出 surface（引用比较：小窗↔全屏切换时变化）
        var boundSurface: Surface? = null
        // 最近一次参数集缓存：换面重建时用缓存 CSD 立即 configure，不依赖远端
        // 在关键帧重复 SPS/PPS（部分远端不重复 → 重建后永久黑屏，切档位重启流
        // 才恢复——用户反馈）。SPS 变化时以新参数集覆盖
        var cachedSps: ByteArray? = null
        var cachedPps: ByteArray? = null
        var cachedDims: Pair<Int, Int>? = null
        // 用缓存参数集重建后只喂 IDR：P 帧依赖前置参考帧，喂给新解码器不产画面
        var needIdr = false
        var pts = 0L
        var fed = 0
        var dropEvery = 1 // 抽帧：1 = 不抽；n = 每 n 帧丢 1（解码器满时自适应升高）
        var dropCounter = 0
        // 诊断统计（每秒打点：喂入/渲染/输入满/抽帧——定位解码吞吐瓶颈）
        var lastStatAt = 0L
        var statFed = 0
        var statFull = 0
        var statSkipped = 0

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
            boundSurface = null
        }

        fun configureCodec(
            surface: Surface,
            sps: ByteArray?,
            pps: ByteArray?,
            dims: Pair<Int, Int>?,
        ): Boolean {
            val fmt =
                MediaFormat.createVideoFormat(MIME, dims?.first ?: 1920, dims?.second ?: 1080).apply {
                    if (sps != null) setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(sps))
                    if (pps != null) setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(pps))
                    // 低延迟模式（Android 12+，多数厂商解码器支持）：减少内部
                    // 缓冲积压——解码输出更快到达 surface（实时性）。不支持时
                    // 解码器忽略该键，无副作用
                    if (Build.VERSION.SDK_INT >= 30) {
                        runCatching { setInteger(MediaFormat.KEY_LOW_LATENCY, 1) }
                    }
                    // 消费速率提示（API 26+）：解码器按此调度缓冲，避免按
                    // 默认（可能更高）帧率预设缓冲
                    val cap = probeDecoderMaxFps(dims?.first ?: 1920, dims?.second ?: 1080)
                    if (cap > 0) {
                        runCatching { setInteger(MediaFormat.KEY_OPERATING_RATE, cap) }
                    }
                }
            var candidate: MediaCodec? = null
            return try {
                candidate = createDecoder()
                candidate.configure(fmt, surface, null, 0)
                candidate.start()
                codec = candidate
                lastDims = dims
                fed = 0
                TermLog.i("screen") { "decoder configured ${dims?.first}x${dims?.second}" }
                true
            } catch (e: Exception) {
                runCatching { candidate?.stop() }
                runCatching { candidate?.release() }
                codec = null
                if (isSurfaceFailure(surface, e)) {
                    TermLog.i("screen") { "surface 在解码器配置期间失效，等待新 surface" }
                } else {
                    TermLog.w("screen") { "decoder configure failed: $e" }
                    onError(ScreenPlayerFailure.Initialization(e.message))
                    running = false
                }
                false
            }
        }

        try {
            while (running) {
                // surface 生命周期（2026-08 修订：一律重建，不再无缝换绑）：
                // setOutputSurface 换绑在真机上不可靠——成功返回但无输出，且
                // boundSurface 已更新、再无重建机会 → 二次进全屏永久黑屏，切帧率/
                // 画质触发 SPS 重配才恢复（用户反馈）。重建代价 ≤ 一个关键帧间隔
                // （keyint≈0.5s），黑窗短暂但确定恢复——正确性优先于无缝。
                // - 全部销毁（列表空）→ 释放 codec
                // - 切换（s ≠ boundSurface）→ 释放，下一关键帧以 currentSurface 重建
                //   （SurfaceView 回调只移除自己的 surface，codec 操作收口在本线程）
                // 局部快照：codec 在 releaseCodec 闭包中被置空，需在判空前取 val
                val sc = codec
                if (sc != null) {
                    val s = currentSurface()
                    if (s !== boundSurface || boundSurface?.isValid != true) {
                        TermLog.i("screen") { "surface 变化（$boundSurface → $s），释放解码器待重建" }
                        releaseCodec()
                        needIdr = true
                    }
                }

                val frame = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                val ps = H264Stream.extractParameterSets(frame)
                val dims = ps.sps?.let { H264Stream.parseSpsDimensions(it) }

                if (ps.sps != null && ps.pps != null) {
                    // 带完整参数集的关键帧：缓存 + 首次 configure / 分辨率变化重配
                    cachedSps = ps.sps
                    cachedPps = ps.pps
                    cachedDims = dims
                    if (codec != null && dims != null && dims != lastDims) {
                        TermLog.i("screen") { "SPS 变化 $lastDims → $dims，重配解码器" }
                        releaseCodec()
                    }
                    if (codec == null) {
                        val surface = currentSurface() ?: continue // 等 UI surface
                        boundSurface = surface
                        if (!configureCodec(surface, ps.sps, ps.pps, dims)) {
                            if (running) continue else return
                        }
                        needIdr = false // 当前帧即 IDR，直接可喂
                    }
                } else {
                    if (codec == null && cachedSps != null && cachedPps != null) {
                        // 换面重建（缓存路径）：不依赖远端在关键帧重复 SPS/PPS，
                        // 用缓存参数集立即 configure，等下一个 IDR（≤0.5s）出画面
                        val surface = currentSurface() ?: continue // 等 UI surface
                        boundSurface = surface
                        TermLog.i("screen") { "surface 变化后用缓存参数集重建解码器" }
                        if (!configureCodec(surface, cachedSps, cachedPps, cachedDims)) {
                            if (running) continue else return
                        }
                        needIdr = true
                    }
                }

                val c = codec ?: continue // 无解码器（无参数集且无缓存）：等关键帧
                if (needIdr && !H264Stream.containsIdr(frame)) {
                    // 起播前丢弃非 IDR：P 帧不能作为新解码器的起播帧
                    if (!renderOutputs(c, boundSurface)) {
                        releaseCodec()
                        needIdr = true
                    }
                    continue
                }
                needIdr = false
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
                    statFull++
                    if (!renderOutputs(c, boundSurface)) {
                        releaseCodec()
                        needIdr = true
                    }
                    continue
                }
                if (dropEvery > 1) {
                    dropCounter++
                    if (dropCounter % dropEvery != 0) {
                        // 被抽掉的帧：占位释放 input buffer（不喂数据）
                        c.queueInputBuffer(idx, 0, 0, pts, 0)
                        statSkipped++
                        if (!renderOutputs(c, boundSurface)) {
                            releaseCodec()
                            needIdr = true
                        }
                        continue
                    }
                    if (dropCounter >= dropEvery * 8) dropCounter = 0
                    // 连续 8 组顺畅：抽帧强度回落
                    if (dropCounter == 0 && dropEvery > 1) dropEvery--
                }
                statFed++
                val buf = c.getInputBuffer(idx)
                if (buf != null && frame.size <= buf.capacity()) {
                    buf.clear()
                    buf.put(frame)
                    c.queueInputBuffer(idx, 0, frame.size, pts, 0)
                    pts += PTS_STEP_US
                } else {
                    c.queueInputBuffer(idx, 0, 0, pts, 0)
                }
                if (!renderOutputs(c, boundSurface)) {
                    releaseCodec()
                    needIdr = true
                    continue
                }

                // 每秒诊断打点：fed/rendered/input-full/skipped——真机定位
                // 「解码跟不上」是硬件极限还是管线瓶颈（旗舰机 1080p 硬解
                // 能力远超 30fps，实测只有 25-30 极可能是缓冲/配置问题）
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastStatAt >= 1000) {
                    val rendered = statRendered
                    statRendered = 0
                    TermLog.i("screen") {
                        "decoder: fed=$statFed rendered=$rendered " +
                            "inputFull=$statFull skipped=$statSkipped dropEvery=$dropEvery queue=${queue.size}"
                    }
                    lastStatAt = nowMs
                    statFed = 0
                    statRendered = 0
                    statFull = 0
                    statSkipped = 0
                }

                // ⚠️ MTK 吞输入 watchdog：喂了 600 帧仍零输出 → 上报（历史 OPPO/MTK 坑）
                if (!firstFrameRendered && fed > 600) {
                    TermLog.w("screen") { "解码器吞输入：fed=$fed 无输出" }
                    onError(ScreenPlayerFailure.NoOutput(fed))
                    running = false
                    break
                }
            }
        } catch (e: InterruptedException) {
            // stop() 中断：正常退出
        } catch (e: Exception) {
            TermLog.w("screen") { "decode loop error: ${e::class.simpleName} ${e.message}" }
            onError(ScreenPlayerFailure.Decoding(e.message ?: e::class.simpleName.orEmpty()))
        } finally {
            releaseCodec()
        }
    }

    private fun renderOutputs(
        codec: MediaCodec,
        surface: Surface?,
    ): Boolean {
        val info = MediaCodec.BufferInfo()
        return try {
            while (true) {
                val idx = codec.dequeueOutputBuffer(info, 0)
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> return true
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
                            statRendered++
                            lastRenderedAtMillis = System.currentTimeMillis()
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
            true
        } catch (e: Exception) {
            if (isSurfaceFailure(surface, e)) {
                TermLog.i("screen") { "surface 渲染期间失效，释放解码器等待重建" }
                false
            } else {
                throw e
            }
        }
    }

    /**
     * Android Emulator 的 goldfish H.264 硬件桥接器可能在首帧后永久阻塞
     * dequeueInputBuffer（即使设置了超时）。模拟器不需要追求硬解功耗，固定使用
     * Android 自带软件解码器更稳定；真机继续交给系统选择硬件解码器。
     */
    private fun createDecoder(): MediaCodec {
        val isEmulator =
            Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
                Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
                Build.HARDWARE.contains("goldfish", ignoreCase = true) ||
                Build.HARDWARE.contains("ranchu", ignoreCase = true)
        if (isEmulator) {
            runCatching { MediaCodec.createByCodecName(ANDROID_SOFTWARE_AVC_DECODER) }
                .onSuccess {
                    TermLog.i("screen") { "emulator uses software decoder $ANDROID_SOFTWARE_AVC_DECODER" }
                    return it
                }.onFailure {
                    TermLog.w("screen") { "software decoder unavailable, fallback to default: ${it.message}" }
                }
        }
        return MediaCodec.createDecoderByType(MIME)
    }
}

@Composable
actual fun ScreenVideoSurface(
    player: ScreenPlayer?,
    modifier: Modifier,
    alignBottom: Boolean,
) {
    if (player == null) {
        Box(modifier.background(ComposeColor.Black))
        return
    }
    // 宽高比适配：解码器上报实际尺寸前铺满（黑底），上报后按比例居中
    // （替代 ExoPlayer PlayerView 的 RESIZE_MODE_FIT，SurfaceView 不会自己 letterbox）。
    // 远程键盘弹出时贴底（alignBottom）：视频底部贴工具栏，黑边留顶部
    val dims = player.decoder.videoDims.value
    Box(
        modifier.background(ComposeColor.Black),
        contentAlignment = if (alignBottom) Alignment.BottomCenter else Alignment.Center,
    ) {
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
                                player.decoder.detachSurface(holder.surface)
                            }
                        },
                    )
                }
            },
            modifier = surfaceModifier,
        )
    }
}

/**
 * 探测硬件解码器在指定分辨率下的帧率上限（MediaCodecList 能力查询）。
 * 取所有 avc 硬解器中最大值；无信息/失败返回 0（未知——调用方不钳制）。
 * 旗舰机 1080p H.264 通常 60+；若探测值 < 推流档位，说明解码器真跟不上，
 * 档位菜单据此隐藏超出项（避免推了也白推——带宽浪费 + 解码器满载排队）。
 */
actual fun probeDecoderMaxFps(
    width: Int,
    height: Int,
): Int {
    val key = "$width x $height"
    decoderProbeCache[key]?.let { return it }
    var best = 0
    try {
        val list = MediaCodecList(MediaCodecList.ALL_CODECS)
        for (info in list.codecInfos) {
            if (info.isEncoder || !info.supportedTypes.any { it.equals(DECODER_MIME, ignoreCase = true) }) continue
            if (Build.VERSION.SDK_INT >= 29 && !info.isHardwareAccelerated) continue
            runCatching {
                val caps = info.getCapabilitiesForType(DECODER_MIME)
                val fps =
                    caps.videoCapabilities
                        .getSupportedFrameRatesFor(width, height)
                        .upper
                        .toInt()
                if (fps > best) best = fps
            }
        }
    } catch (_: Throwable) {
        // 探测失败不阻断：返回 0 = 未知
    }
    decoderProbeCache[key] = best
    return best
}

package dev.termish.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.viewinterop.UIKitView
import dev.termish.util.TermLog
import kotlin.concurrent.Volatile
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readValue
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.AVSampleBufferDisplayLayer
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSDate
import platform.Foundation.NSLock
import platform.UIKit.UIColor
import platform.UIKit.UIView
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import screen_player.termish_screen_enqueue
import screen_player.termish_screen_format_create
import screen_player.termish_screen_format_release
import screen_player.termish_screen_h264_hardware_supported
import screen_player.termish_screen_layer_error_code
import screen_player.termish_screen_layer_state

private const val FRAME_QUEUE_CAPACITY = 4
private const val MAX_CONSECUTIVE_LAYER_FAILURES = 3

private data class PreparedFrame(
    val avcc: ByteArray,
    val sps: ByteArray?,
    val pps: ByteArray?,
    val dims: Pair<Int, Int>?,
    val isIdr: Boolean,
)

/**
 * iOS 实现：完整 H.264 Annex-B 帧转换成 AVCC sample，交给
 * AVSampleBufferDisplayLayer（底层 VideoToolbox 硬解）直接渲染。
 *
 * 所有 AVFoundation/CoreMedia 操作都收口到主线程；网络线程只写容量为 4 的
 * 丢旧保新队列，避免解码跟不上时延迟持续增长。每个 Compose surface 拥有独立
 * display layer，小窗与全屏过渡期可同时工作，销毁其中一个不会影响另一个。
 */
@OptIn(ExperimentalForeignApi::class)
actual class ScreenPlayer actual constructor(
    private val targetFps: Int,
    private val onReady: () -> Unit,
    private val onError: (ScreenPlayerFailure) -> Unit,
) {
    private val queueLock = NSLock()
    private val frameQueue = ArrayDeque<PreparedFrame>(FRAME_QUEUE_CAPACITY)
    private val views = mutableListOf<ScreenVideoView>()

    @Volatile private var running = false
    private var drainScheduled = false
    private var format: COpaquePointer? = null
    private var cachedSps: ByteArray? = null
    private var cachedPps: ByteArray? = null
    private var readyReported = false

    @Volatile private var receivedFrames = 0L

    @Volatile private var renderedFrames = 0L

    @Volatile private var droppedFrames = 0L

    @Volatile
    actual var lastRenderedAtMillis = 0L
        private set

    @Volatile
    actual var renderSurfaceAttached = false
        private set

    actual val videoDims: MutableState<Pair<Int, Int>?> = mutableStateOf(null)

    actual fun start() {
        queueLock.lock()
        try {
            if (running) return
            running = true
        } finally {
            queueLock.unlock()
        }
        TermLog.i("screen") { "iOS H.264 player started targetFps=$targetFps" }
        scheduleDrain()
    }

    actual fun feed(data: ByteArray) {
        // TCP 读取运行在 IO dispatcher：在这里完成 Annex-B 扫描与 AVCC 复制，
        // 避免 60fps 大帧转换占用 UIKit 主线程。
        val parameterSets = H264Stream.extractParameterSets(data)
        val rawSps = parameterSets.sps?.let(H264Stream::stripStartCode)
        val rawPps = parameterSets.pps?.let(H264Stream::stripStartCode)
        val prepared =
            PreparedFrame(
                avcc = H264Stream.toAvccSample(data) ?: return,
                sps = rawSps,
                pps = rawPps,
                dims = rawSps?.let(H264Stream::parseSpsDimensions),
                isIdr = H264Stream.containsIdr(data),
            )
        var shouldSchedule = false
        queueLock.lock()
        try {
            if (!running) return
            receivedFrames++
            while (frameQueue.size >= FRAME_QUEUE_CAPACITY) {
                frameQueue.removeFirst()
                droppedFrames++
            }
            frameQueue.addLast(prepared)
            if (!drainScheduled) {
                drainScheduled = true
                shouldSchedule = true
            }
        } finally {
            queueLock.unlock()
        }
        if (shouldSchedule) dispatch_async(dispatch_get_main_queue()) { drainFrames() }
    }

    actual fun stop() {
        queueLock.lock()
        try {
            running = false
            frameQueue.clear()
        } finally {
            queueLock.unlock()
        }
        dispatch_async(dispatch_get_main_queue()) {
            releaseFormat()
            views.forEach {
                it.sampleLayer.flushAndRemoveImage()
                it.needsIdr = true
            }
            cachedSps = null
            cachedPps = null
            videoDims.value = null
            readyReported = false
            TermLog.i("screen") { "iOS H.264 player stopped" }
        }
    }

    actual fun metrics(): ScreenPlayerMetrics =
        ScreenPlayerMetrics(
            receivedFrames = receivedFrames,
            renderedFrames = renderedFrames,
            droppedFrames = droppedFrames,
            queueDepth =
                queueLock.run {
                    lock()
                    try {
                        frameQueue.size
                    } finally {
                        unlock()
                    }
                },
        )

    internal fun attachView(view: ScreenVideoView) {
        if (!views.contains(view)) views += view
        renderSurfaceAttached = views.isNotEmpty()
        scheduleDrain()
    }

    internal fun detachView(view: ScreenVideoView) {
        views.remove(view)
        renderSurfaceAttached = views.isNotEmpty()
        view.sampleLayer.flushAndRemoveImage()
        view.sampleLayer.removeFromSuperlayer()
    }

    private fun scheduleDrain() {
        var shouldSchedule = false
        queueLock.lock()
        try {
            if (running && frameQueue.isNotEmpty() && !drainScheduled) {
                drainScheduled = true
                shouldSchedule = true
            }
        } finally {
            queueLock.unlock()
        }
        if (shouldSchedule) dispatch_async(dispatch_get_main_queue()) { drainFrames() }
    }

    private fun drainFrames() {
        if (!running || views.isEmpty()) {
            queueLock.lock()
            try {
                drainScheduled = false
            } finally {
                queueLock.unlock()
            }
            return
        }

        while (running) {
            val frame =
                queueLock.run {
                    lock()
                    try {
                        if (frameQueue.isEmpty()) {
                            drainScheduled = false
                            null
                        } else {
                            frameQueue.removeFirst()
                        }
                    } finally {
                        unlock()
                    }
                } ?: return
            decodeFrame(frame)
        }

        queueLock.lock()
        try {
            drainScheduled = false
        } finally {
            queueLock.unlock()
        }
    }

    private fun decodeFrame(frame: PreparedFrame) {
        var parametersChanged = false
        frame.sps?.let { sps ->
            if (cachedSps?.contentEquals(sps) != true) {
                cachedSps = sps
                videoDims.value = frame.dims
                parametersChanged = true
            }
        }
        frame.pps?.let { pps ->
            if (cachedPps?.contentEquals(pps) != true) {
                cachedPps = pps
                parametersChanged = true
            }
        }

        if (parametersChanged) {
            releaseFormat()
            views.forEach {
                it.sampleLayer.flush()
                it.needsIdr = true
            }
        }

        if (format == null) {
            if (!frame.isIdr || !createFormat()) return
            TermLog.i("screen") { "iOS decoder configured ${videoDims.value}" }
        }

        var rendered = false
        views.toList().forEach { view ->
            if (view.needsIdr && !frame.isIdr) return@forEach
            val status = enqueue(view.sampleLayer, frame.avcc, frame.isIdr)
            if (status == 0) {
                view.needsIdr = false
                val layerState = termish_screen_layer_state(view.sampleLayer)
                when {
                    layerState > 0 -> {
                        rendered = true
                        lastRenderedAtMillis = NSDate().timeIntervalSince1970.times(1000).toLong()
                        view.consecutiveFailures = 0
                        if (!readyReported) {
                            readyReported = true
                            onReady()
                        }
                    }

                    layerState < 0 -> recoverLayer(view)
                    else -> view.consecutiveFailures = 0
                }
            } else {
                TermLog.w("screen") { "iOS sample enqueue failed: OSStatus=$status" }
                recoverLayer(view)
            }
        }
        if (rendered) renderedFrames++
    }

    private fun createFormat(): Boolean {
        val sps = cachedSps ?: return false
        val pps = cachedPps ?: return false
        var created: COpaquePointer? = null
        var status = -1
        sps.usePinned { pinnedSps ->
            pps.usePinned { pinnedPps ->
                memScoped {
                    val statusOut = alloc<IntVar>()
                    created =
                        termish_screen_format_create(
                            pinnedSps.addressOf(0).reinterpret(),
                            sps.size.toULong(),
                            pinnedPps.addressOf(0).reinterpret(),
                            pps.size.toULong(),
                            statusOut.ptr,
                        )
                    status = statusOut.value
                }
            }
        }
        format = created
        if (created == null) {
            running = false
            onError(ScreenPlayerFailure.Initialization("CoreMedia OSStatus=$status"))
            TermLog.w("screen") { "iOS format creation failed: OSStatus=$status" }
            return false
        }
        return true
    }

    private fun enqueue(
        layer: AVSampleBufferDisplayLayer,
        avcc: ByteArray,
        isIdr: Boolean,
    ): Int {
        val currentFormat = format ?: return -1
        return avcc.usePinned { pinned ->
            termish_screen_enqueue(
                layer,
                currentFormat,
                pinned.addressOf(0).reinterpret(),
                avcc.size.toULong(),
                if (isIdr) 1 else 0,
            )
        }
    }

    private fun recoverLayer(view: ScreenVideoView) {
        val errorCode = termish_screen_layer_error_code(view.sampleLayer)
        view.sampleLayer.flushAndRemoveImage()
        view.needsIdr = true
        view.consecutiveFailures++
        if (view.consecutiveFailures >= MAX_CONSECUTIVE_LAYER_FAILURES) {
            view.consecutiveFailures = 0
            onError(ScreenPlayerFailure.Decoding("AVFoundation error=$errorCode"))
        }
    }

    private fun releaseFormat() {
        format?.let { termish_screen_format_release(it) }
        format = null
    }
}

@OptIn(ExperimentalForeignApi::class)
internal class ScreenVideoView : UIView(frame = CGRectZero.readValue()) {
    val sampleLayer =
        AVSampleBufferDisplayLayer().apply {
            videoGravity = AVLayerVideoGravityResizeAspect
        }
    var needsIdr = true
    var consecutiveFailures = 0

    init {
        backgroundColor = UIColor.blackColor
        userInteractionEnabled = false
        layer.addSublayer(sampleLayer)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        sampleLayer.frame = bounds
    }
}

@OptIn(ExperimentalForeignApi::class)
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
    val dims = player.videoDims.value
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
        UIKitView(
            factory = {
                ScreenVideoView().also(player::attachView)
            },
            modifier = surfaceModifier,
            onRelease = player::detachView,
        )
    }
}

/** iOS 没有公开逐分辨率 FPS 查询；硬解可用时按当前档位上限开放 60fps。 */
@OptIn(ExperimentalForeignApi::class)
actual fun probeDecoderMaxFps(
    width: Int,
    height: Int,
): Int = if (termish_screen_h264_hardware_supported() != 0) 60 else 0

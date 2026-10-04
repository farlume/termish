package dev.termish.screen

import dev.termish.ssh.SshExecChannel
import dev.termish.util.TermLog
import dev.termish.util.ioDispatcher
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.datetime.Clock

/** TCP/SSH 视频协议单帧上限，防损坏长度头触发无界内存分配。 */
internal const val SCREEN_TCP_MAX_FRAME_BYTES = 4 * 1024 * 1024

/** 客户端 → relay 控制消息上限（含 17B 固定头与 UTF-8 文本）。 */
internal const val SCREEN_TCP_MAX_CONTROL_BYTES = 64 * 1024

/** relay 安装时生成的 256-bit token，以 64 个十六进制字符传输。 */
internal const val SCREEN_AUTH_TOKEN_HEX_LENGTH = 64
internal const val SCREEN_TCP_HEARTBEAT_INTERVAL_MS = 2_000L
internal const val SCREEN_TCP_FEEDBACK_INTERVAL_MS = 1_000L
internal const val SCREEN_TCP_STATUS_BUSY = 3
internal const val SCREEN_TCP_STATUS_CAPTURE_PERMISSION_MISSING = 4

internal fun screenStatusRejectsConnection(status: Int): Boolean = status == SCREEN_TCP_STATUS_BUSY || status == SCREEN_TCP_STATUS_CAPTURE_PERMISSION_MISSING

internal const val SCREEN_FEEDBACK_REQUEST_KEYFRAME = 1

private val SCREEN_AUTH_MAGIC = byteArrayOf('T'.code.toByte(), 'H'.code.toByte(), 'A'.code.toByte(), '1'.code.toByte())
private val SCREEN_VIDEO_V2_MAGIC = byteArrayOf('T'.code.toByte(), 'H'.code.toByte(), 'V'.code.toByte(), '2'.code.toByte())
private val SCREEN_FEEDBACK_MAGIC = byteArrayOf('T'.code.toByte(), 'H'.code.toByte(), 'F'.code.toByte(), '1'.code.toByte())
private const val SCREEN_VIDEO_V2_HEADER_BYTES = 21
private const val SCREEN_FEEDBACK_BYTES = 40

data class ScreenVideoPacket(
    val data: ByteArray,
    val sequence: Long = -1,
    val sentAtMicros: Long = 0,
    val flags: Int = 0,
) {
    val isKeyframe: Boolean
        get() = flags and 1 != 0
}

data class ScreenClientFeedback(
    val receivedFps: Int,
    val renderedFps: Int,
    val bitrateKbps: Int,
    val droppedPermille: Int,
    val decoderBusyFrames: Int,
    val jitterMillis: Int,
    val queueDepth: Int,
    val lastSequence: Long,
    val requestKeyframe: Boolean,
)

data class ScreenTransportMetrics(
    val receivedFrames: Long = 0,
    val receivedBytes: Long = 0,
    val lastVideoAtMillis: Long = 0,
    val lostFrames: Long = 0,
    val jitterMillis: Int = 0,
    val lastSequence: Long = -1,
)

internal fun parseScreenVideoPacket(payload: ByteArray): ScreenVideoPacket {
    if (payload.size < SCREEN_VIDEO_V2_HEADER_BYTES || !payload.startsWith(SCREEN_VIDEO_V2_MAGIC)) {
        return ScreenVideoPacket(payload)
    }
    return ScreenVideoPacket(
        data = payload.copyOfRange(SCREEN_VIDEO_V2_HEADER_BYTES, payload.size),
        sequence = readLong(payload, 4),
        sentAtMicros = readLong(payload, 12),
        flags = payload[20].toInt() and 0xff,
    )
}

internal fun encodeScreenFeedback(feedback: ScreenClientFeedback): ByteArray =
    ByteArray(SCREEN_FEEDBACK_BYTES).also { data ->
        SCREEN_FEEDBACK_MAGIC.copyInto(data)
        data[4] = 1
        data[5] = if (feedback.requestKeyframe) SCREEN_FEEDBACK_REQUEST_KEYFRAME.toByte() else 0
        writeShort(data, 6, feedback.queueDepth.coerceIn(0, 65_535))
        writeInt(data, 8, feedback.receivedFps)
        writeInt(data, 12, feedback.renderedFps)
        writeInt(data, 16, feedback.bitrateKbps)
        writeInt(data, 20, feedback.droppedPermille)
        writeInt(data, 24, feedback.decoderBusyFrames)
        writeInt(data, 28, feedback.jitterMillis)
        writeLong(data, 32, feedback.lastSequence)
    }

internal fun parseScreenFeedback(data: ByteArray): ScreenClientFeedback? {
    if (data.size != SCREEN_FEEDBACK_BYTES || !data.startsWith(SCREEN_FEEDBACK_MAGIC) || data[4].toInt() != 1) {
        return null
    }
    return ScreenClientFeedback(
        receivedFps = readInt(data, 8),
        renderedFps = readInt(data, 12),
        bitrateKbps = readInt(data, 16),
        droppedPermille = readInt(data, 20),
        decoderBusyFrames = readInt(data, 24),
        jitterMillis = readInt(data, 28),
        queueDepth = readShort(data, 6),
        lastSequence = readLong(data, 32),
        requestKeyframe = data[5].toInt() and SCREEN_FEEDBACK_REQUEST_KEYFRAME != 0,
    )
}

/**
 * 仅在确实需要解码重同步时请求 IDR。启动阶段的软件解码器短暂繁忙、队列主动
 * 丢旧保新都由码率/FPS 闭环处理；每次因此重启编码器反而会延长首帧稳定时间。
 */
internal fun shouldRequestScreenKeyframe(
    receivedFrames: Long,
    renderedFrames: Long,
    networkLostFrames: Long,
    playerDroppedFrames: Long,
): Boolean =
    networkLostFrames > 0 ||
        (receivedFrames >= 10 && renderedFrames == 0L && playerDroppedFrames > 0)

/**
 * TCP 视频字节流解析器。
 *
 * relay → 客户端协议：连接首包固定为 `THS1 + status`，随后重复
 * `[4B 大端长度][THV2 视频包或 THS1 状态更新]`。TCP/SSH read 可在任意位置拆包或粘包，
 * 因此解析器保留跨 read 缓冲，并可一次产出多帧。
 */
internal class ScreenTcpFrameParser(
    private val onStatus: (Int) -> Unit,
    private val onFrame: (ByteArray) -> Unit,
) {
    private var buffer = ByteArray(64 * 1024)
    private var size = 0
    private var statusPending = true

    fun push(data: ByteArray) {
        if (data.isEmpty()) return
        ensureCapacity(size + data.size)
        data.copyInto(buffer, size)
        size += data.size
        drain()
    }

    private fun drain() {
        var offset = 0
        if (statusPending) {
            if (size < 5) return
            require(
                buffer[0] == 'T'.code.toByte() &&
                    buffer[1] == 'H'.code.toByte() &&
                    buffer[2] == 'S'.code.toByte() &&
                    buffer[3] == '1'.code.toByte(),
            ) { "TCP 视频协议缺少状态头" }
            onStatus(buffer[4].toInt() and 0xff)
            statusPending = false
            offset = 5
        }

        while (size - offset >= 4) {
            val frameSize = readInt(buffer, offset)
            require(frameSize in 1..SCREEN_TCP_MAX_FRAME_BYTES) {
                "TCP 视频帧长度非法：$frameSize"
            }
            if (size - offset - 4 < frameSize) break
            val frame = ByteArray(frameSize)
            buffer.copyInto(frame, 0, offset + 4, offset + 4 + frameSize)
            offset += 4 + frameSize
            if (frameSize == 5 && frame.startsWith(SCREEN_STATUS_MAGIC.encodeToByteArray())) {
                onStatus(frame[4].toInt() and 0xff)
            } else {
                onFrame(frame)
            }
        }

        if (offset > 0) {
            buffer.copyInto(buffer, 0, offset, size)
            size -= offset
        }
    }

    private fun ensureCapacity(need: Int) {
        val maxBuffer = SCREEN_TCP_MAX_FRAME_BYTES + 64 * 1024 + 9
        require(need <= maxBuffer) { "TCP 视频缓冲超限：$need" }
        if (need <= buffer.size) return
        var capacity = buffer.size
        while (capacity < need) capacity = (capacity * 2).coerceAtMost(maxBuffer)
        buffer = buffer.copyOf(capacity)
    }
}

/** 客户端 → relay 控制协议：`[4B 大端长度][THC1 控制包]`。 */
internal fun frameScreenTcpControl(data: ByteArray): ByteArray {
    require(data.size in 1..SCREEN_TCP_MAX_CONTROL_BYTES)
    val framed = ByteArray(4 + data.size)
    writeInt(framed, 0, data.size)
    data.copyInto(framed, 4)
    return framed
}

/** direct-tcpip 建连首包：`[4B 长度][THA1][64B hex token]`。 */
internal fun frameScreenTcpAuth(token: String): ByteArray {
    require(
        token.length == SCREEN_AUTH_TOKEN_HEX_LENGTH &&
            token.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' },
    ) { "Invalid screen authentication token" }
    return frameScreenTcpControl(SCREEN_AUTH_MAGIC + token.encodeToByteArray())
}

private fun readInt(
    data: ByteArray,
    offset: Int,
): Int =
    ((data[offset].toInt() and 0xff) shl 24) or
        ((data[offset + 1].toInt() and 0xff) shl 16) or
        ((data[offset + 2].toInt() and 0xff) shl 8) or
        (data[offset + 3].toInt() and 0xff)

private fun readShort(
    data: ByteArray,
    offset: Int,
): Int = ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)

private fun readLong(
    data: ByteArray,
    offset: Int,
): Long {
    var value = 0L
    repeat(8) { index -> value = (value shl 8) or (data[offset + index].toLong() and 0xff) }
    return value
}

private fun writeInt(
    data: ByteArray,
    offset: Int,
    value: Int,
) {
    data[offset] = (value ushr 24).toByte()
    data[offset + 1] = (value ushr 16).toByte()
    data[offset + 2] = (value ushr 8).toByte()
    data[offset + 3] = value.toByte()
}

private fun writeShort(
    data: ByteArray,
    offset: Int,
    value: Int,
) {
    data[offset] = (value ushr 8).toByte()
    data[offset + 1] = value.toByte()
}

private fun writeLong(
    data: ByteArray,
    offset: Int,
    value: Long,
) {
    repeat(8) { index -> data[offset + index] = (value ushr (56 - index * 8)).toByte() }
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

/**
 * 复用 SSH `direct-tcpip` 的双向视频会话。
 *
 * relay 只监听远端 `127.0.0.1`，客户端通过已认证 SSH 连接访问：不依赖额外
 * 公网端口映射，也不会把屏幕画面与远程控制接口暴露在公网。视频方向使用长度
 * 帧；控制方向同样加长度头，避免 TCP 粘包破坏可变长文本消息边界。
 */
class ScreenTcpSession(
    private val channel: SshExecChannel,
    private val scope: CoroutineScope,
    private val authToken: String,
    private val onVideoPacket: (ScreenVideoPacket) -> Unit,
    private val onDisconnected: () -> Unit,
    private val playerMetrics: () -> ScreenPlayerMetrics = { ScreenPlayerMetrics() },
    /** 返回 false 表示 relay 已明确拒绝本次连接，不应按意外断线触发自动重连。 */
    private val onStatus: (Int) -> Boolean = { true },
    /** Wayland 首次门户授权需要用户确认，允许调用方延长首包等待时间。 */
    private val firstVideoTimeoutMillis: Long = SCREEN_FIRST_VIDEO_TIMEOUT_MS,
    /** 时间源注入，便于活性判定测试及避免平台时间 API 泄漏到 commonMain。 */
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private var job: Job? = null
    private var watchdogJob: Job? = null
    private val disconnectLock = Mutex()

    @Volatile
    private var active = false

    @Volatile
    private var startedAtMillis = 0L

    @Volatile
    private var lastVideoAtMillis = 0L

    @Volatile
    private var receivedVideoPackets = 0L

    @Volatile
    private var receivedVideoBytes = 0L

    @Volatile
    private var inferredLostFrames = 0L

    @Volatile
    private var lastVideoSequence = -1L

    @Volatile
    private var jitterMillis = 0

    private var jitterEstimate = 0.0

    private var previousArrivalAtMillis = 0L
    private var previousSentAtMicros = 0L

    private var lastHeartbeatAtMillis = 0L
    private var lastFeedbackAtMillis = 0L
    private var previousFeedbackTransport = ScreenTransportMetrics()
    private var previousFeedbackPlayer = ScreenPlayerMetrics()

    fun start() {
        if (active) return
        active = true
        startedAtMillis = nowMillis()
        lastVideoAtMillis = 0L
        receivedVideoPackets = 0L
        receivedVideoBytes = 0L
        inferredLostFrames = 0L
        lastVideoSequence = -1L
        jitterMillis = 0
        jitterEstimate = 0.0
        previousArrivalAtMillis = 0L
        previousSentAtMicros = 0L
        lastHeartbeatAtMillis = 0L
        lastFeedbackAtMillis = startedAtMillis
        previousFeedbackTransport = ScreenTransportMetrics()
        previousFeedbackPlayer = playerMetrics()
        // relay 在认证成功前不会发状态/视频，也不会替换旧客户端。
        channel.write(frameScreenTcpAuth(authToken))
        job =
            scope.launch(ioDispatcher()) {
                val parser =
                    ScreenTcpFrameParser(
                        onStatus = { status ->
                            if (!onStatus(status)) {
                                active = false
                                watchdogJob?.cancel()
                            }
                        },
                        onFrame = { frame ->
                            val packet = parseScreenVideoPacket(frame)
                            val arrivedAt = nowMillis()
                            updatePacketTiming(packet, arrivedAt)
                            lastVideoAtMillis = arrivedAt
                            receivedVideoPackets++
                            receivedVideoBytes += packet.data.size
                            onVideoPacket(packet)
                        },
                    )
                try {
                    while (active && coroutineContext.isActive) {
                        val data = channel.read() ?: break
                        parser.push(data)
                    }
                } catch (e: Exception) {
                    TermLog.w("screen") { "SSH TCP 视频读取结束：${e::class.simpleName} ${e.message}" }
                } finally {
                    channel.close()
                    notifyDisconnected("eof")
                }
            }
        watchdogJob =
            scope.launch(ioDispatcher()) {
                while (active && coroutineContext.isActive) {
                    delay(1_000)
                    val now = nowMillis()
                    if (now - lastHeartbeatAtMillis >= SCREEN_TCP_HEARTBEAT_INTERVAL_MS) {
                        lastHeartbeatAtMillis = now
                        sendRaw(ScreenControlPacket.encode(ScreenControlPacket.TYPE_HEARTBEAT, 0f, 0f))
                    }
                    if (now - lastFeedbackAtMillis >= SCREEN_TCP_FEEDBACK_INTERVAL_MS) {
                        sendFeedback(now)
                    }
                    val packetCount = receivedVideoPackets
                    if (
                        isScreenVideoStalled(
                            nowMillis = now,
                            lastVideoAtMillis = lastVideoAtMillis,
                            startedAtMillis = startedAtMillis,
                            hasReceivedVideo = packetCount > 0,
                            firstVideoTimeoutMillis = firstVideoTimeoutMillis,
                        )
                    ) {
                        val baseline = if (packetCount > 0) lastVideoAtMillis else startedAtMillis
                        TermLog.w("screen") {
                            "SSH TCP 视频停滞：idle=${now - baseline}ms packets=$packetCount，主动重连"
                        }
                        channel.close()
                        notifyDisconnected("stalled")
                        break
                    }
                }
            }
    }

    fun sendRaw(data: ByteArray) {
        if (!active) return
        runCatching { channel.write(frameScreenTcpControl(data)) }
    }

    fun close() {
        active = false
        channel.close()
        job?.cancel()
        job = null
        watchdogJob?.cancel()
        watchdogJob = null
    }

    fun isActive(): Boolean = active

    fun metrics(): ScreenTransportMetrics =
        ScreenTransportMetrics(
            receivedFrames = receivedVideoPackets,
            receivedBytes = receivedVideoBytes,
            lastVideoAtMillis = lastVideoAtMillis,
            lostFrames = inferredLostFrames,
            jitterMillis = jitterMillis,
            lastSequence = lastVideoSequence,
        )

    private fun updatePacketTiming(
        packet: ScreenVideoPacket,
        arrivedAtMillis: Long,
    ) {
        if (packet.sequence >= 0) {
            if (lastVideoSequence >= 0 && packet.sequence > lastVideoSequence + 1) {
                inferredLostFrames += packet.sequence - lastVideoSequence - 1
            }
            lastVideoSequence = maxOf(lastVideoSequence, packet.sequence)
        }
        if (packet.sentAtMicros > 0 && previousSentAtMicros > 0 && previousArrivalAtMillis > 0) {
            val arrivalDelta = arrivedAtMillis - previousArrivalAtMillis
            val senderDelta = (packet.sentAtMicros - previousSentAtMicros) / 1_000
            val variation = kotlin.math.abs(arrivalDelta - senderDelta).coerceAtMost(5_000)
            jitterEstimate += (variation - jitterEstimate) / 16.0
            jitterMillis = jitterEstimate.toInt()
        }
        if (packet.sentAtMicros > 0) previousSentAtMicros = packet.sentAtMicros
        previousArrivalAtMillis = arrivedAtMillis
    }

    private fun sendFeedback(now: Long) {
        val elapsed = (now - lastFeedbackAtMillis).coerceAtLeast(1)
        val transport = metrics()
        val player = playerMetrics()
        val received = (transport.receivedFrames - previousFeedbackTransport.receivedFrames).coerceAtLeast(0)
        val bytes = (transport.receivedBytes - previousFeedbackTransport.receivedBytes).coerceAtLeast(0)
        val networkLost = (transport.lostFrames - previousFeedbackTransport.lostFrames).coerceAtLeast(0)
        val rendered = (player.renderedFrames - previousFeedbackPlayer.renderedFrames).coerceAtLeast(0)
        val playerDropped = (player.droppedFrames - previousFeedbackPlayer.droppedFrames).coerceAtLeast(0)
        val busy = (player.decoderBusyFrames - previousFeedbackPlayer.decoderBusyFrames).coerceAtLeast(0)
        val totalDropped = networkLost + playerDropped
        val totalFrames = received + networkLost
        val droppedPermille = if (totalFrames > 0) (totalDropped * 1_000 / totalFrames).toInt() else 0
        val requestKeyframe =
            shouldRequestScreenKeyframe(
                receivedFrames = received,
                renderedFrames = rendered,
                networkLostFrames = networkLost,
                playerDroppedFrames = playerDropped,
            )
        sendRaw(
            encodeScreenFeedback(
                ScreenClientFeedback(
                    receivedFps = (received * 1_000 / elapsed).toInt(),
                    renderedFps = (rendered * 1_000 / elapsed).toInt(),
                    bitrateKbps = (bytes * 8 / elapsed).toInt(),
                    droppedPermille = droppedPermille.coerceIn(0, 1_000),
                    decoderBusyFrames = busy.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    jitterMillis = transport.jitterMillis,
                    queueDepth = player.queueDepth,
                    lastSequence = transport.lastSequence,
                    requestKeyframe = requestKeyframe,
                ),
            ),
        )
        previousFeedbackTransport = transport
        previousFeedbackPlayer = player
        lastFeedbackAtMillis = now
    }

    /** 播放层检测到卡死时复用统一断线出口，避免只显示错误却永久停在旧画面。 */
    fun fail(reason: String) {
        if (!active) return
        channel.close()
        notifyDisconnected(reason)
    }

    /** reader EOF 与 watchdog 可能同时到达；只允许一个来源触发重连。 */
    private fun notifyDisconnected(reason: String) {
        if (!active || !disconnectLock.tryLock()) return
        try {
            if (!active) return
            active = false
            watchdogJob?.cancel()
            TermLog.i("screen") { "SSH TCP 视频会话结束：$reason" }
            onDisconnected()
        } finally {
            disconnectLock.unlock()
        }
    }
}

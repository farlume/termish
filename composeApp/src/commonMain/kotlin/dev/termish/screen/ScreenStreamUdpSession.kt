package dev.termish.screen

import dev.termish.mosh.MoshUdpSocket
import dev.termish.util.TermLog
import dev.termish.util.ioDispatcher
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Clock

/** 心跳包 magic（手机 → relay）：relay 识别后更新客户端地址，不转发给 ffmpeg。 */
val SCREEN_HEARTBEAT_MAGIC = byteArrayOf(0x54, 0x48, 0x42, 0x01) // "THB\x01"

/**
 * 重载包 magic（新会话首包）：relay 收到后重启 ffmpeg（重读推流参数）。
 * 画质/帧率切换的生效路径——否则漫游语义下旧 ffmpeg 永不重启，
 * conf 写了也自读不到（UDP 版每次连接不再自动换新 ffmpeg，区别于旧 TCP 版）。
 */
val SCREEN_RELOAD_MAGIC = byteArrayOf(0x54, 0x48, 0x42, 0x02) // "THB\x02"

/** 控制包 magic（手机 → relay）：远程操作（触摸→鼠标/滚轮）。"THC1" */
const val SCREEN_CONTROL_MAGIC = "THC1"

/** 状态包 magic（relay → 手机）："THS1" + 1B 状态（0=权限 OK，1=缺辅助功能权限）。 */
const val SCREEN_STATUS_MAGIC = "THS1"

/** 远程操作控制包：类型 + 归一化坐标（0-1）+ 增量。 */
object ScreenControlPacket {
    const val TYPE_MOVE = 0
    const val TYPE_DOWN = 1
    const val TYPE_UP = 2
    const val TYPE_SCROLL = 3

    /** 文本键（软键盘上屏字符/中文 IME）：payload = UTF-8 字符。 */
    const val TYPE_TEXT = 4

    /** 键码键（工具栏特殊键/组合键）：extra = macOS 虚拟键码，x = 修饰掩码。 */
    const val TYPE_KEY = 5

    /** 右键按下/抬起（虚拟鼠标右键区）。 */
    const val TYPE_RIGHT_DOWN = 6
    const val TYPE_RIGHT_UP = 7

    /** 虚拟鼠标原子点击：在目标处点击后恢复 Mac 的实体鼠标位置。 */
    const val TYPE_CLICK = 8
    const val TYPE_RIGHT_CLICK = 9

    /** 虚拟鼠标长按拖动：独立序列让 relay 使用平台原生拖动事件并在结束后恢复实体鼠标。 */
    const val TYPE_VIRTUAL_LEFT_DOWN = 10
    const val TYPE_VIRTUAL_LEFT_DRAG = 11
    const val TYPE_VIRTUAL_LEFT_UP = 12

    /** TCP owner 租约心跳；relay 只更新时间，不注入任何本地输入。 */
    const val TYPE_HEARTBEAT = 13

    /** 修饰掩码（TYPE_KEY 的 x 字段）。 */
    const val MOD_COMMAND = 1
    const val MOD_SHIFT = 2
    const val MOD_CONTROL = 4
    const val MOD_OPTION = 8

    /** macOS 虚拟键码（Carbon kVK，relay CGEvent 用）。 */
    object KeyCode {
        const val C = 8
        const val V = 9
        const val Q = 12
        const val RETURN = 36
        const val TAB = 48
        const val SPACE = 49
        const val BACKSPACE = 51
        const val ESC = 53
        const val CAPS = 57
        const val LEFT = 123
        const val RIGHT = 124
        const val DOWN = 125
        const val UP = 126
    }

    private fun f4(v: Float): ByteArray {
        val b = v.toRawBits()
        return byteArrayOf((b shr 24).toByte(), (b shr 16).toByte(), (b shr 8).toByte(), b.toByte())
    }

    private fun i4(v: Int): ByteArray = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

    /** 17B：magic(4) + type(1) + x f32(4) + y f32(4) + extra i32(4)。 */
    fun encode(
        type: Int,
        x: Float,
        y: Float,
        extra: Int = 0,
    ): ByteArray =
        SCREEN_CONTROL_MAGIC.encodeToByteArray() +
            byteArrayOf(type.toByte()) +
            f4(x.coerceIn(0f, 1f)) +
            f4(y.coerceIn(0f, 1f)) +
            i4(extra)

    /** 文本键包：magic + type(4) + UTF-8 字符（无坐标，x/y 为 0）。 */
    fun encodeText(text: String): ByteArray =
        SCREEN_CONTROL_MAGIC.encodeToByteArray() +
            byteArrayOf(TYPE_TEXT.toByte()) +
            f4(0f) +
            f4(0f) +
            i4(0) +
            text.encodeToByteArray()

    /** 键码键包：extra = macOS 虚拟键码，x = 修饰掩码（float 位域）。 */
    fun encodeKey(
        keyCode: Int,
        mods: Int,
    ): ByteArray =
        SCREEN_CONTROL_MAGIC.encodeToByteArray() +
            byteArrayOf(TYPE_KEY.toByte()) +
            f4(mods.toFloat()) +
            f4(0f) +
            i4(keyCode)
}

/** 状态包解析：返回状态值（见 [SCREEN_STATUS_MAGIC]）；非状态包返回 null。 */
fun parseScreenStatus(data: ByteArray): Int? {
    if (data.size < 5 || !data.copyOfRange(0, 4).decodeToString().startsWith(SCREEN_STATUS_MAGIC)) return null
    return data[4].toInt()
}

/**
 * 带丢帧反馈的心跳：THB\x01 + 1 字节丢帧率（percent，0-100）。
 * relay 据此自适应发送速率（高丢帧→降速→接收缓冲不打满→丢帧率回落），
 * 打破「高码率洪流持续打满 WiFi 缓冲→持续丢帧」的恶性循环。
 */
fun heartbeatWithLoss(lossPercent: Int): ByteArray = byteArrayOf(0x54, 0x48, 0x42, 0x01, lossPercent.coerceIn(0, 100).toByte())

/**
 * 屏幕推流的 UDP 漫游会话（方案 A）：视频流从 SSH/TCP 搬到 UDP，
 * 获得 mosh 式的漫游能力——断网不显示断开、网络恢复后续传。
 *
 * 漫游三要素（对应 mosh 客户端语义）：
 * 1. **心跳**：每秒发一个 magic 包，让 relay 持续知道手机最新地址 + 保活 NAT 映射
 * 2. **端口轮换**：10s 收不到视频包就换一个新源端口重新「打洞」
 *   （断网后旧端口的 NAT 映射可能失效）
 * 3. **永不超时**：已连上后绝不因断网主动退出，网络恢复即续传
 *
 * 与 [dev.termish.mosh.KmpMoshSession] 的关系：复用它的 [MoshUdpSocket]（UDP 抽象）
 * 与分片格式，但视频流单向、无需状态同步，因此这里没有 transport/预测层，
 * 收包直接进 [ScreenStreamReceiver] 重组。
 *
 * 线程模型：socket 收包在 ioDispatcher 协程，心跳在独立协程；两个协程只通过
 * @Volatile 字段 + socket 操作交互（与 KmpMoshSession 单线程事件循环不同，视频流
 * 无需有序处理，多协程更简单）。
 */
class ScreenStreamUdpSession(
    private val ip: String,
    private val port: Int,
    private val scope: CoroutineScope,
    /** 重组出的视频包（解压后字节，喂给播放器）。 */
    private val onVideoPacket: (ByteArray) -> Unit,
    /** 链路健康度：距上次收到视频包的秒数，值变化才回调。 */
    private val onLinkStatus: (Int) -> Unit = {},
    /** relay 状态包回调（如辅助功能权限缺失）；值变化才回调。 */
    private val onRelayStatus: (Int) -> Unit = {},
    /** 时间源注入（测试用）。 */
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    companion object {
        private const val HEARTBEAT_INTERVAL_MS = 1000L
        private const val PORT_HOP_INTERVAL_MS = 10_000L
    }

    private val receiver = ScreenStreamReceiver()

    @Volatile
    private var socket: MoshUdpSocket? = null

    private var receiveJob: Job? = null
    private var heartbeatJob: Job? = null

    @Volatile
    private var active = false

    @Volatile
    private var lastHeard = -1L

    private var lastHeartbeat = 0L
    private var lastHopAt = 0L
    private var lastReportedLostSecs = 0

    @Volatile private var lastReportedStatus = -1

    // 重组完整/丢弃计数（喂心跳丢帧反馈；不打印——高频路径）
    @Volatile private var fragOk = 0

    @Volatile private var fragDrop = 0

    /**
     * 本地消费慢丢弃的分片计数（inbound 队列满→丢旧分片）。
     * 与网络丢包分离：本地丢过分片时，后续重组放弃归因于本地（解码/处理
     * 跟不上），不喂 AIMD——否则解码瓶颈会误报网络拥塞，relay 错降速到
     * 2MB/s 地板、画质崩塌（清单遗留 #1：帧级顶替误报）。
     */
    @Volatile private var localFragDrops = 0

    /** 最近一次统计窗口的网络丢帧率（percent，喂心跳反馈）。 */
    private fun recentLossPercent(): Int {
        val ok = fragOk
        val drop = fragDrop
        val total = ok + drop
        return if (total == 0) 0 else drop * 100 / total
    }

    /**
     * 发送远程操作控制包（触摸→鼠标/滚轮）。与心跳同 socket 即时发送：
     * 不排队不重传（丢包容忍——下一帧事件自带完整坐标，属位置语义）。
     */
    fun sendControl(
        type: Int,
        x: Float,
        y: Float,
        extra: Int = 0,
    ) {
        val s = socket ?: return
        runCatching { s.send(ScreenControlPacket.encode(type, x, y, extra)) }
    }

    /** 发送原始控制包（文本键等带 payload 的包）。 */
    fun sendRaw(data: ByteArray) {
        val s = socket ?: return
        runCatching { s.send(data) }
    }

    /** 首包发 reload（重启远端 ffmpeg 重读推流参数；发送成功后清位）。 */
    @Volatile private var reloadPending = true

    private val sockLock = Mutex()

    fun start() {
        if (active) return
        active = true
        lastHopAt = nowMs()
        // 建 socket 必须在 io 协程：MoshUdpSocket 构造做 DNS 解析 + connect，
        // 在主线程调用会抛 NetworkOnMainThreadException（Android；调用方
        // ScreenSession 的 scope 是 Compose 主线程协程）
        scope.launch(ioDispatcher()) { openSocket() }
        launchHeartbeat()
    }

    private suspend fun openSocket() {
        sockLock.withLock {
            closeSocketLocked()
            if (!active) return
            val s = MoshUdpSocket(ip, port)
            socket = s
            // 接收/处理双阶段：socket 线程只做 receive + 入队（每秒上千包的热路径，
            // 重组/解压/zlib 移到独立协程——单线程串行会在消费慢时打满 socket
            // 缓冲，内核 RcvbufErrors 实测每秒 +80 丢包）
            val inbound = Channel<ByteArray>(2048)
            scope.launch(ioDispatcher()) {
                try {
                    while (active && coroutineContext.isActive) {
                        val dg = s.receive(1000) ?: continue
                        lastHeard = nowMs()
                        // 丢旧保新：处理跟不上时丢旧分片（半可靠语义，丢片=丢一帧）
                        while (!inbound.trySend(dg.data).isSuccess) {
                            if (inbound.tryReceive().getOrNull() == null) break
                            // 本地消费慢：计数归因，不喂 AIMD（见 [localFragDrops]）
                            localFragDrops++
                        }
                    }
                } catch (_: Exception) {
                    // socket 关闭或错误：心跳协程会按需重建
                }
            }
            receiveJob =
                scope.launch(ioDispatcher()) {
                    try {
                        while (active && coroutineContext.isActive) {
                            val data = withTimeoutOrNull(1_000) { inbound.receive() } ?: continue
                            // relay 状态包（THS1）：不参与分片重组，直通回调
                            val status = parseScreenStatus(data)
                            if (status != null) {
                                if (status != lastReportedStatus) {
                                    lastReportedStatus = status
                                    onRelayStatus(status)
                                }
                                continue
                            }
                            val pkt = receiver.onDatagram(data)
                            if (pkt != null) {
                                fragOk++
                                onVideoPacket(pkt)
                            } else if (receiver.lastAbandoned) {
                                receiver.lastAbandoned = false
                                // 重组放弃：若近期本地丢过分片（inbound 满），归因本地
                                // 消费慢（解码跟不上）；否则是网络丢包，喂 AIMD
                                if (localFragDrops > 0) {
                                    localFragDrops--
                                } else {
                                    fragDrop++
                                }
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
        }
    }

    private fun launchHeartbeat() {
        heartbeatJob =
            scope.launch(ioDispatcher()) {
                while (active && coroutineContext.isActive) {
                    val now = nowMs()
                    // 心跳：每秒发 magic 包保活 + 地址更新；首包是 reload
                    // （重启远端 ffmpeg 重读 conf，见 [SCREEN_RELOAD_MAGIC]）
                    val s = socket
                    if (now - lastHeartbeat >= HEARTBEAT_INTERVAL_MS && s != null) {
                        val pkt =
                            if (reloadPending) {
                                SCREEN_RELOAD_MAGIC
                            } else {
                                heartbeatWithLoss(recentLossPercent())
                            }
                        runCatching { s.send(pkt) }
                        reloadPending = false
                        lastHeartbeat = now
                    }
                    // 端口轮换：10s 收不到视频包 → 换源端口重新打洞（漫游核心）
                    if (lastHeard != -1L &&
                        now - lastHeard >= PORT_HOP_INTERVAL_MS &&
                        now - lastHopAt >= PORT_HOP_INTERVAL_MS
                    ) {
                        TermLog.w("screen") { "UDP 无包 ${(now - lastHeard) / 1000}s，换源端口重打洞" }
                        openSocket()
                        lastHopAt = now
                    }
                    // 链路健康度上报（值变化才回调）
                    val lostSecs = if (lastHeard == -1L) 0 else ((now - lastHeard) / 1000).toInt()
                    if (lostSecs != lastReportedLostSecs) {
                        lastReportedLostSecs = lostSecs
                        onLinkStatus(lostSecs)
                    }
                    delay(250)
                }
            }
    }

    private fun closeSocketLocked() {
        receiveJob?.cancel()
        receiveJob = null
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
    }

    fun isActive(): Boolean = active

    fun close() {
        active = false
        heartbeatJob?.cancel()
        heartbeatJob = null
        scope.launch(ioDispatcher()) {
            sockLock.withLock { closeSocketLocked() }
        }
    }
}

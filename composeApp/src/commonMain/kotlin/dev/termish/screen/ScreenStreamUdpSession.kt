package dev.termish.screen

import dev.termish.mosh.MoshUdpSocket
import dev.termish.util.TermLog
import dev.termish.util.ioDispatcher
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 心跳包 magic（手机 → relay）：relay 识别后更新客户端地址，不转发给 ffmpeg。 */
val SCREEN_HEARTBEAT_MAGIC = byteArrayOf(0x54, 0x48, 0x42, 0x01) // "THB\x01"

/**
 * 重载包 magic（新会话首包）：relay 收到后重启 ffmpeg（重读推流参数）。
 * 画质/帧率切换的生效路径——否则漫游语义下旧 ffmpeg 永不重启，
 * conf 写了也自读不到（UDP 版每次连接不再自动换新 ffmpeg，区别于旧 TCP 版）。
 */
val SCREEN_RELOAD_MAGIC = byteArrayOf(0x54, 0x48, 0x42, 0x02) // "THB\x02"

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
    /** 时间源注入（测试用）。 */
    private val nowMs: () -> Long = { System.currentTimeMillis() },
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

    /** 首包发 reload（重启远端 ffmpeg 重读推流参数；发送成功后清位）。 */
    @Volatile private var reloadPending = true

    private val sockLock = Any()

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

    private fun openSocket() {
        synchronized(sockLock) {
            closeSocketLocked()
            val s = MoshUdpSocket(ip, port)
            socket = s
            // 接收/处理双阶段：socket 线程只做 receive + 入队（每秒上千包的热路径，
            // 重组/解压/zlib 移到独立协程——单线程串行会在消费慢时打满 socket
            // 缓冲，内核 RcvbufErrors 实测每秒 +80 丢包）
            val inbound = java.util.concurrent.LinkedBlockingQueue<ByteArray>(2048)
            scope.launch(ioDispatcher()) {
                try {
                    while (active && coroutineContext.isActive) {
                        val dg = s.receive(1000) ?: continue
                        lastHeard = nowMs()
                        // 丢旧保新：处理跟不上时丢旧分片（半可靠语义，丢片=丢一帧）
                        while (!inbound.offer(dg.data)) {
                            if (inbound.poll() == null) break
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
                            val data = inbound.poll(1, java.util.concurrent.TimeUnit.SECONDS) ?: continue
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
                    kotlinx.coroutines.delay(250)
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
        synchronized(sockLock) { closeSocketLocked() }
        heartbeatJob?.cancel()
    }
}

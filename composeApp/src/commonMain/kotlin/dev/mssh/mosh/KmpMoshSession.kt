package dev.mssh.mosh

import dev.mssh.util.ioDispatcher
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 纯 Kotlin 实现的 mosh 客户端会话（替代原生 libmoshclient.so）。
 *
 * 线程模型：transport 非线程安全，全部访问限定在事件循环协程内。
 * UI 线程的输入/resize/close 与 socket 收包都经 [events] Channel 汇入，
 * 收包协程只负责阻塞读 socket 并投递，保证输入即时唤醒（不等 receive 超时）。
 *
 * 与原生路径的渲染差异：原生 mosh-client 输出 ANSI 字节流经 onOutput 喂给 UI
 * 模拟器；KMP 版本直接维护服务端状态的影子终端，状态推进时把影子 buffer
 * 内容同步给 UI buffer（[onStateUpdate]）。
 */
class KmpMoshSession(
    ip: String,
    port: Int,
    key: String,
    columns: Int,
    rows: Int,
    private val scope: CoroutineScope,
    /** 最新影子状态（在会话协程里回调；实现方应拷贝后切回主线程渲染）。 */
    private val onStateUpdate: (ShadowTerminalView) -> Unit,
    private val onExit: (String?) -> Unit,
) {
    /** 供 UI 读取的影子状态视图。 */
    class ShadowTerminalView internal constructor(
        internal val shadow: ShadowTerminal,
    ) {
        val buffer: dev.mssh.term.TerminalBuffer get() = shadow.buffer
        val echoAck: ULong get() = shadow.echoAck
    }

    private sealed class Event {
        class Packet(val data: ByteArray, val congestionExperienced: Boolean) : Event()
        class Input(val data: ByteArray) : Event()
        class Resize(val cols: Int, val rows: Int) : Event()
        object Close : Event()
    }

    // ---- mosh Connection 客户端端口轮换（协议常量） ----
    private val PORT_HOP_INTERVAL = 10_000L
    private val MAX_OLD_SOCKET_AGE = 60_000L
    private val MAX_PORTS_OPEN = 10

    private val mark = TimeSource.Monotonic.markNow()
    private fun nowMs(): Long = mark.elapsedNow().inWholeMilliseconds

    private val initialCols = columns
    private val initialRows = rows
    private val remoteIp = ip
    private val remotePort = port

    private val sockets = ArrayDeque<MoshUdpSocket>().apply { addLast(MoshUdpSocket(remoteIp, remotePort)) }
    private var sendSocket = sockets.first()
    private val socketJobs = ArrayDeque<Job>()
    private var lastHopAt = 0L
    private val events = Channel<Event>(Channel.UNLIMITED)
    private val prediction = PredictionLayer(::nowMs)

    private val transport = MoshTransport(
        ip = ip,
        initialCols = columns,
        initialRows = rows,
        key = key,
        nowMs = ::nowMs,
        sendDatagram = { data ->
            try {
                sendSocket.send(data)
            } catch (e: Exception) {
                println("mosh UDP 发送失败: ${e.message}")
                SendResult.FAILED
            }
        },
        onNewState = { shadow ->
            // 确认态到达：丢弃本地预测，UI 切回确认态
            prediction.onConfirmed(shadow)
            pushToUi(shadow)
        },
    )

    private fun pushToUi(shadow: ShadowTerminal) {
        shadow.onTitleChange = { t -> titleCallback(t) }
        shadow.onClipboardWrite = { s -> clipboardCallback(s) }
        onStateUpdate(ShadowTerminalView(shadow))
    }

    private var titleCallback: (String) -> Unit = {}
    private var clipboardCallback: (String) -> Unit = {}
    private var loopJob: Job? = null

    /** 仅事件循环线程读写；close 后由循环线程置 false。跨线程读（close/UI）走 @Volatile。 */
    @Volatile
    private var active = true
    @Volatile
    private var peerSeen = false
    private var startedAt = 0L

    fun setTitleCallback(cb: (String) -> Unit) {
        titleCallback = cb
    }

    fun setClipboardCallback(cb: (String) -> Unit) {
        clipboardCallback = cb
    }

    fun start() {
        startedAt = nowMs()
        transport.setSendDelay(1) // mosh 客户端：击键尽快发出（默认 8ms 是服务端语义）
        // 初始窗口尺寸随第一帧上报（mosh 会话客户端::main_init 的 Resize 入队）
        events.trySend(Event.Resize(initialCols, initialRows))
        launchReader(sendSocket)
        loopJob = scope.launch(Dispatchers.Default) {
            try {
                loop()
            } catch (e: Exception) {
                if (active) {
                    active = false
                    println("KmpMoshSession 会话异常: ${e.stackTraceToString()}")
                    onExit("mosh 会话异常：${e.message}")
                }
            }
        }
    }

    /** 每个 socket 一个收包协程：阻塞读，投递后由事件循环统一处理。 */
    private fun launchReader(s: MoshUdpSocket) {
        val job = scope.launch(ioDispatcher()) {
            try {
                while (true) {
                    val dg = s.receive(60_000) ?: continue
                    events.send(Event.Packet(dg.data, dg.congestionExperienced))
                }
            } catch (_: Exception) {
                // socket 关闭或错误：事件循环靠定时器/心跳退出，无需额外通知
            }
        }
        socketJobs.addLast(job)
    }

    private suspend fun loop() {
        while (active) {
            val waitMs = transport.waitTime().let {
                when {
                    it == Int.MAX_VALUE -> 1000 // 无定时需求时周期醒来驱动心跳
                    it <= 0 -> 1
                    else -> it.coerceAtMost(1000)
                }
            }
            val ev = withTimeoutOrNull(waitMs.toLong()) { events.receive() }
            when (ev) {
                is Event.Packet -> {
                    val pkt = transport.decryptDatagram(ev.data)
                    if (pkt != null) {
                        transport.processPacket(pkt, ev.congestionExperienced)
                        peerSeen = true
                    }
                }
                is Event.Input -> {
                    transport.pushBytes(ev.data)
                    // 本地预测回显：高 RTT 时按键先在确认态分叉上渲染（下划线标记），
                    // 服务器确认后校正——与 mosh 预测引擎 同思路的简化版
                    if (prediction.onUserInput(ev.data, transport.srttMs())) {
                        prediction.currentForDisplay()?.let { pushToUi(it) }
                    }
                }
                is Event.Resize -> transport.pushResize(ev.cols, ev.rows)
                is Event.Close -> transport.startShutdown()
                null -> {} // 超时，到点 tick
            }
            transport.tick()
            maybeHopPort()

            // 预测悬挂过久（服务器未确认或期间有输出）：丢弃并回确认态
            if (prediction.glitchTimedOut()) {
                prediction.onConfirmed(transport.latestRemote)
                pushToUi(transport.latestRemote)
            }

            // mosh-client：15s 无新远端状态 → 主动进入 shutdown（随后由超时兜底退出）
            if (transport.hasPeer() && !transport.shutdownInProgress &&
                nowMs() - transport.lastHeardMs() > 15_000
            ) {
                transport.startShutdown()
            }

            // 15s 无对端应答视为连接失败（UDP 端口不通等）
            if (!peerSeen && nowMs() - startedAt > 15_000) {
                active = false
                cleanup()
                onExit("mosh 连接超时：UDP 端口不可达（检查端口转发/防火墙）")
                return
            }
            // 正常关闭路径：对端 ack 了我们的 shutdown，或我们已确认对端 shutdown——
            // 都算干净退出（mosh client.cc 的 shutdown_acknowledged /
            // counterparty_shutdown_ack_sent）
            if ((transport.shutdownInProgress && transport.shutdownAcknowledged()) ||
                transport.counterpartyShutdownAckSent()
            ) {
                active = false
                cleanup()
                onExit(null)
                return
            }
            if (transport.shutdownAckTimedOut()) {
                active = false
                cleanup()
                onExit(null)
                return
            }
        }
    }

    /**
     * mosh Connection::send 的客户端端口轮换：
     * 10s 无对端 ack 且 10s 没换过源端口 → 换新 socket；旧 socket 保留收包，
     * 新端口稳定 60s 后清掉旧的（prune_sockets），最多同时开 10 个。
     */
    private fun maybeHopPort() {
        val now = nowMs()
        // 先剪除：最后一次 hop 后稳定超过 60s，说明链路已恢复，只留最新端口
        if (sockets.size > 1 && now - lastHopAt >= MAX_OLD_SOCKET_AGE) {
            while (sockets.size > 1) {
                val old = sockets.removeFirst()
                socketJobs.removeFirst()?.cancel()
                try {
                    old.close()
                } catch (_: Exception) {
                }
            }
        }
        while (sockets.size > MAX_PORTS_OPEN) {
            val old = sockets.removeFirst()
            socketJobs.removeFirst()?.cancel()
            try {
                old.close()
            } catch (_: Exception) {
            }
        }

        if (transport.shutdownInProgress) return
        if (now - lastHopAt < PORT_HOP_INTERVAL) return
        if (now - transport.sentStateAckedTimestamp() < PORT_HOP_INTERVAL) return

        val next = MoshUdpSocket(remoteIp, remotePort)
        sockets.addLast(next)
        launchReader(next)
        sendSocket = next
        lastHopAt = now
    }

    private fun cleanup() {
        for (s in sockets) {
            try {
                s.close()
            } catch (_: Exception) {
            }
        }
        for (j in socketJobs) {
            j.cancel()
        }
    }

    fun isActive(): Boolean = active

    fun resize(columns: Int, rows: Int) {
        if (active) events.trySend(Event.Resize(columns, rows))
    }

    fun sendData(data: ByteArray) {
        if (active) events.trySend(Event.Input(data))
    }

    fun close() {
        if (!active) return
        if (!peerSeen) {
            // 从未收到对端任何数据报：没有握手可言，直接结束
            active = false
            cleanup()
            onExit(null)
            return
        }
        events.trySend(Event.Close)
    }
}

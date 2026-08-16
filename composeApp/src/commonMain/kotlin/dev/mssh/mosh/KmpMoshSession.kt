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
        class Packet(val data: ByteArray) : Event()
        class Input(val data: ByteArray) : Event()
        class Resize(val cols: Int, val rows: Int) : Event()
        object Close : Event()
    }

    private val mark = TimeSource.Monotonic.markNow()
    private fun nowMs(): Long = mark.elapsedNow().inWholeMilliseconds

    private val initialCols = columns
    private val initialRows = rows

    private val socket = MoshUdpSocket(ip, port)
    private val events = Channel<Event>(Channel.UNLIMITED)

    private val transport = MoshTransport(
        ip = ip,
        initialCols = columns,
        initialRows = rows,
        key = key,
        nowMs = ::nowMs,
        sendDatagram = { data ->
            try {
                socket.send(data)
            } catch (e: Exception) {
                println("mosh UDP 发送失败: ${e.message}")
            }
        },
        onNewState = { shadow ->
            shadow.onTitleChange = { t -> titleCallback(t) }
            shadow.onClipboardWrite = { s -> clipboardCallback(s) }
            onStateUpdate(ShadowTerminalView(shadow))
        },
    )

    private var titleCallback: (String) -> Unit = {}
    private var clipboardCallback: (String) -> Unit = {}
    private var loopJob: Job? = null
    private var readerJob: Job? = null

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
        // 收包协程：阻塞读，投递后由事件循环统一处理
        readerJob = scope.launch(ioDispatcher()) {
            try {
                while (true) {
                    val data = socket.receive(60_000) ?: continue
                    events.send(Event.Packet(data))
                }
            } catch (_: Exception) {
                // socket 关闭或错误：事件循环靠定时器/心跳退出，无需额外通知
            }
        }
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
                        transport.processPacket(pkt)
                        peerSeen = true
                    }
                }
                is Event.Input -> transport.pushBytes(ev.data)
                is Event.Resize -> transport.pushResize(ev.cols, ev.rows)
                is Event.Close -> transport.startShutdown()
                null -> {} // 超时，到点 tick
            }
            transport.tick()

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

    private fun cleanup() {
        try {
            socket.close()
        } catch (_: Exception) {
        }
        readerJob?.cancel()
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

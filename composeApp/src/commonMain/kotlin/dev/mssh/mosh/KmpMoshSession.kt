package dev.mssh.mosh

import dev.mssh.term.TerminalBuffer
import dev.mssh.term.TerminalEmulator
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 纯 Kotlin 实现的 mosh 客户端会话（替代原生 libmoshclient.so）。
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
        val buffer: TerminalBuffer get() = shadow.buffer
        val echoAck: ULong get() = shadow.echoAck
    }

    private val mark = TimeSource.Monotonic.markNow()
    private fun nowMs(): Long = mark.elapsedNow().inWholeMilliseconds

    private val socket = MoshUdpSocket(ip, port)
    private val transport = MoshTransport(
        initialCols = columns,
        initialRows = rows,
        key = key,
        nowMs = ::nowMs,
        sendDatagram = { data ->
            try {
                socket.send(data)
            } catch (_: Exception) {
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
    private var job: Job? = null
    // 跨线程可见性靠协程调度保证（写读均经由 Default dispatcher 的 happens-before）。
    private var active = true
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
        job = scope.launch(Dispatchers.Default) {
            try {
                loop()
            } catch (e: Exception) {
                if (active) onExit("mosh 会话异常：${e.message}")
            }
        }
    }

    private fun loop() {
        while (active) {
            val waitMs = transport.waitTime().let {
                when {
                    it == Int.MAX_VALUE -> 1000 // 无定时需求时仍周期醒来看 socket/心跳
                    it <= 0 -> 1
                    else -> it.coerceAtMost(1000)
                }
            }
            val data = try {
                socket.receive(waitMs)
            } catch (_: Exception) {
                null
            }
            if (data != null) {
                val pkt = transport.decryptDatagram(data)
                if (pkt != null) {
                    transport.processPacket(pkt)
                    peerSeen = true
                }
            }
            transport.tick()

            // 15s 无对端应答视为连接失败（UDP 端口不通等）
            if (!peerSeen && nowMs() - startedAt > 15_000) {
                active = false
                onExit("mosh 连接超时：UDP 端口不可达（检查端口转发/防火墙）")
                return
            }
            if (transport.shutdownAckTimedOut()) {
                active = false
                onExit(null)
                return
            }
        }
    }

    fun isActive(): Boolean = active

    fun resize(columns: Int, rows: Int) {
        transport.pushResize(columns, rows)
    }

    fun sendData(data: ByteArray) {
        transport.pushBytes(data)
    }

    fun close() {
        if (!active) return
        transport.startShutdown()
        // 给关闭握手留一点时间（尽力而为），随后强制结束
        scope.launch(Dispatchers.Default) {
            val deadline = nowMs() + 500
            while (active && nowMs() < deadline) {
                transport.tick()
                kotlinx.coroutines.delay(20)
            }
            active = false
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }
}

package dev.mssh.ssh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Mosh 客户端会话：由纯 Kotlin 实现（dev.mssh.mosh 包，SSP 协议）。
 * UI 通过 sendData/resize 驱动，影子终端状态经 onStateUpdate 同步进 UI buffer。
 */
interface MoshSession {
    fun isActive(): Boolean
    fun resize(columns: Int, rows: Int)
    fun sendData(data: ByteArray)
    fun close()
}

/** 解析 `mosh-server new` 输出里的 `MOSH CONNECT <port> <key>`。 */
fun parseMoshConnect(output: String): Pair<Int, String>? {
    val m = Regex("MOSH CONNECT (\\d+) ([A-Za-z0-9+/=]+)").find(output) ?: return null
    return m.groupValues[1].toIntOrNull()?.let { it to m.groupValues[2] }
}

/** mosh-server 引导命令（-c 256：远端 TERM=xterm-256color，与本机渲染能力一致；
 *  -c 8 会让 mosh-server 置 TERM=xterm，远端程序降级到 8 色）。 */
const val MOSH_SERVER_BOOTSTRAP = "mosh-server new -c 256 -l LANG=en_US.UTF-8"

/**
 * 纯 Kotlin mosh 客户端（dev.mssh.mosh 包）：把影子终端状态同步进 [uiBuffer]。
 */
fun createKmpMoshSession(
    ip: String,
    port: Int,
    key: String,
    columns: Int,
    rows: Int,
    scope: kotlinx.coroutines.CoroutineScope,
    uiBuffer: dev.mssh.term.TerminalBuffer,
    onTitle: (String) -> Unit,
    onClipboard: (String) -> Unit,
    onExit: (String?) -> Unit,
    /** 状态已同步进 [uiBuffer]，请求 UI 重绘（TerminalBuffer 不是 Compose 状态，
     *  不通知的话新内容要等下一个偶发 frame 变更才上屏）。 */
    onFrame: () -> Unit,
    /** 收到对端首包（mosh 会话真正建立）时回调。 */
    onPeerConnected: () -> Unit,
    /** 链路健康度：距上次收到对端包的秒数（0~3 正常；UI 据此显示失联提示）。 */
    onLinkStatus: (Int) -> Unit,
): MoshSession {
    // 渲染合并：CONFLATED 通道只保留最新状态，突发更新时最多一个主线程拷贝在途
    val pending = Channel<dev.mssh.mosh.KmpMoshSession.ShadowTerminalView>(Channel.CONFLATED)
    scope.launch(Dispatchers.Main) {
        for (view in pending) {
            // 行级/字段级增量比对：视觉无变化的推送（如预测收编后的重放）不触发重绘
            if (uiBuffer.copyContentFrom(view.buffer)) onFrame()
        }
    }
    val session = dev.mssh.mosh.KmpMoshSession(
        ip = ip,
        port = port,
        key = key,
        columns = columns,
        rows = rows,
        scope = scope,
        onStateUpdate = { view ->
            // 拷贝到 UI buffer 必须在主线程（Compose 渲染同时在读）；
            // 通道消费协程已固定在 Main，这里只投递
            pending.trySend(view)
        },
        onExit = onExit,
        onPeerConnected = onPeerConnected,
        onLinkStatus = onLinkStatus,
    )
    session.setTitleCallback(onTitle)
    session.setClipboardCallback(onClipboard)
    session.start()
    return object : MoshSession {
        override fun isActive(): Boolean = session.isActive()
        override fun resize(columns: Int, rows: Int) = session.resize(columns, rows)
        override fun sendData(data: ByteArray) = session.sendData(data)
        override fun close() = session.close()
    }
}

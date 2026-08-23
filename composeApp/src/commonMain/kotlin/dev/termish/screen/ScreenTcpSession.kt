package dev.termish.screen

import dev.termish.ssh.SshExecChannel
import dev.termish.util.TermLog
import dev.termish.util.ioDispatcher
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** TCP/SSH 视频协议单帧上限，防损坏长度头触发无界内存分配。 */
internal const val SCREEN_TCP_MAX_FRAME_BYTES = 4 * 1024 * 1024

/** 客户端 → relay 控制消息上限（含 17B 固定头与 UTF-8 文本）。 */
internal const val SCREEN_TCP_MAX_CONTROL_BYTES = 64 * 1024

/** relay 安装时生成的 256-bit token，以 64 个十六进制字符传输。 */
internal const val SCREEN_AUTH_TOKEN_HEX_LENGTH = 64

private val SCREEN_AUTH_MAGIC = byteArrayOf('T'.code.toByte(), 'H'.code.toByte(), 'A'.code.toByte(), '1'.code.toByte())

/**
 * TCP 视频字节流解析器。
 *
 * relay → 客户端协议：连接首包固定为 `THS1 + status`，随后重复
 * `[4B 大端帧长][Annex-B 完整帧]`。TCP/SSH read 可在任意位置拆包或粘包，
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
            onFrame(frame)
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
    private val onVideoPacket: (ByteArray) -> Unit,
    private val onDisconnected: () -> Unit,
    private val onStatus: (Int) -> Unit = {},
) {
    private var job: Job? = null

    @Volatile
    private var active = false

    fun start() {
        if (active) return
        active = true
        // relay 在认证成功前不会发状态/视频，也不会替换旧客户端。
        channel.write(frameScreenTcpAuth(authToken))
        job =
            scope.launch(ioDispatcher()) {
                val parser = ScreenTcpFrameParser(onStatus, onVideoPacket)
                try {
                    while (active && coroutineContext.isActive) {
                        val data = channel.read() ?: break
                        parser.push(data)
                    }
                } catch (e: Exception) {
                    TermLog.w("screen") { "SSH TCP 视频读取结束：${e::class.simpleName} ${e.message}" }
                } finally {
                    channel.close()
                    if (active) {
                        active = false
                        onDisconnected()
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
    }

    fun isActive(): Boolean = active
}

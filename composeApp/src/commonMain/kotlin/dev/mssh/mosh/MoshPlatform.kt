package dev.mssh.mosh

/**
 * 阻塞式 UDP 套接字抽象（在 IO 线程/协程里使用）。
 * receive 返回 null 表示超时（用于驱动 SSP tick）。
 */
/** 平台 send 结果：TOO_LARGE（EMSGSIZE）用于 MTU 降级反馈。 */
internal enum class SendResult { OK, TOO_LARGE, FAILED }

/** 收到的数据报。[congestionExperienced] 是 ECN CE 位标记（平台暂不提供，恒 false）。 */
internal class UdpDatagram(
    val data: ByteArray,
    val congestionExperienced: Boolean = false,
)

internal expect class MoshUdpSocket(ip: String, port: Int) {
    fun send(data: ByteArray): SendResult
    /** timeoutMillis 内未收到数据返回 null。 */
    fun receive(timeoutMillis: Int): UdpDatagram?
    fun close()
}

internal expect fun zlibCompress(data: ByteArray): ByteArray
internal expect fun zlibDecompress(data: ByteArray): ByteArray

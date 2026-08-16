package dev.mssh.mosh

/**
 * 阻塞式 UDP 套接字抽象（在 IO 线程/协程里使用）。
 * receive 返回 null 表示超时（用于驱动 SSP tick）。
 */
internal expect class MoshUdpSocket(ip: String, port: Int) {
    fun send(data: ByteArray)
    /** timeoutMillis 内未收到数据返回 null。 */
    fun receive(timeoutMillis: Int): ByteArray?
    fun close()
}

internal expect fun zlibCompress(data: ByteArray): ByteArray
internal expect fun zlibDecompress(data: ByteArray): ByteArray

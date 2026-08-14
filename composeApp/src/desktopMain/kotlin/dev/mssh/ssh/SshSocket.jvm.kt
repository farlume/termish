package dev.mssh.ssh

import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

actual class SshSocket actual constructor() {
    private var socket: Socket? = null
    private var input: java.io.InputStream? = null
    private var output: java.io.OutputStream? = null

    actual fun connect(host: String, port: Int) {
        try {
            val s = Socket()
            s.connect(InetSocketAddress(host, port), 10000)
            s.tcpNoDelay = true
            s.soTimeout = 0
            socket = s
            input = s.getInputStream()
            output = s.getOutputStream()
        } catch (e: Exception) {
            if (e is SocketTimeoutException) throw SshException("连接超时: $host:$port", e)
            throw SshException("无法连接 $host:$port: ${e.message}", e)
        }
    }

    actual fun write(data: ByteArray) {
        try {
            output?.write(data)
            output?.flush()
        } catch (e: Exception) {
            throw SshException("发送数据失败: ${e.message}", e)
        }
    }

    actual fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        try {
            val n = input?.read(buffer, offset, length) ?: -1
            if (n == 0 && length > 0) {
                // Blocking read should not return 0; treat as retry
                return read(buffer, offset, length)
            }
            return n
        } catch (e: Exception) {
            throw SshException("读取数据失败: ${e.message}", e)
        }
    }

    actual fun close() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        input = null
        output = null
    }
}

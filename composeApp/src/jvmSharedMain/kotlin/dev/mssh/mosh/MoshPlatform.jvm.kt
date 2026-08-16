package dev.mssh.mosh

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.zip.Deflater
import java.util.zip.Inflater

internal actual class MoshUdpSocket actual constructor(ip: String, port: Int) {
    private val remoteAddr = InetAddress.getByName(ip)
    actual val isIpv6: Boolean = remoteAddr is java.net.Inet6Address
    private val socket = DatagramSocket().apply {
        connect(remoteAddr, port)
    }
    private val buf = ByteArray(4096)

    actual fun send(data: ByteArray): SendResult = try {
        socket.send(DatagramPacket(data, data.size))
        SendResult.OK
    } catch (e: java.net.SocketException) {
        // Linux/Android 的 Java 层不暴露 errno；"Message too long" 是 EMSGSIZE 的常见文本
        if (e.message?.contains("too long", ignoreCase = true) == true) {
            SendResult.TOO_LARGE
        } else {
            SendResult.FAILED
        }
    } catch (_: Exception) {
        SendResult.FAILED
    }

    actual fun receive(timeoutMillis: Int): UdpDatagram? {
        socket.soTimeout = timeoutMillis.coerceAtLeast(1)
        val pkt = DatagramPacket(buf, buf.size)
        return try {
            socket.receive(pkt)
            UdpDatagram(pkt.data.copyOf(pkt.length))
        } catch (_: SocketTimeoutException) {
            null
        }
    }

    actual fun close() {
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }
}

internal actual fun zlibCompress(data: ByteArray): ByteArray {
    val deflater = Deflater()
    deflater.setInput(data)
    deflater.finish()
    val buf = ByteArray(data.size + 64)
    val out = java.io.ByteArrayOutputStream()
    try {
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            out.write(buf, 0, n)
        }
    } finally {
        deflater.end()
    }
    return out.toByteArray()
}

internal actual fun zlibDecompress(data: ByteArray): ByteArray {
    val inflater = Inflater()
    inflater.setInput(data)
    val buf = ByteArray(8192)
    val out = java.io.ByteArrayOutputStream()
    try {
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0) {
                if (inflater.needsInput() || inflater.needsDictionary()) break
            }
            out.write(buf, 0, n)
        }
    } finally {
        inflater.end()
    }
    return out.toByteArray()
}

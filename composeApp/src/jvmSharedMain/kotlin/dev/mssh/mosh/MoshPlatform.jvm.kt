package dev.mssh.mosh

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.zip.Deflater
import java.util.zip.Inflater

internal actual class MoshUdpSocket actual constructor(ip: String, port: Int) {
    private val socket = DatagramSocket().apply {
        connect(InetAddress.getByName(ip), port)
    }
    private val buf = ByteArray(4096)

    actual fun send(data: ByteArray) {
        socket.send(DatagramPacket(data, data.size))
    }

    actual fun receive(timeoutMillis: Int): ByteArray? {
        socket.soTimeout = timeoutMillis.coerceAtLeast(1)
        val pkt = DatagramPacket(buf, buf.size)
        return try {
            socket.receive(pkt)
            pkt.data.copyOf(pkt.length)
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

package dev.mssh.ssh

import kotlinx.cinterop.*
import platform.posix.*

@OptIn(ExperimentalForeignApi::class)
actual class SshSocket actual constructor() {
    private var fd: Int = -1

    actual fun connect(host: String, port: Int) {
        if (fd >= 0) close()
        val rc = memScoped {
            val hints = alloc<addrinfo>()
            hints.ai_family = AF_UNSPEC
            hints.ai_socktype = SOCK_STREAM
            hints.ai_protocol = IPPROTO_TCP
            val res = alloc<CPointerVar<addrinfo>>()
            val gai = getaddrinfo(host, port.toString(), hints.ptr, res.ptr)
            if (gai != 0) {
                throw SshException("DNS 解析失败: $host (${gaiStrerror(gai)})")
            }
            var lastErr = 0
            var result = -1
            var cur = res.value
            while (cur != null) {
                val s = socket(cur.pointed.ai_family, cur.pointed.ai_socktype, cur.pointed.ai_protocol)
                if (s < 0) {
                    lastErr = errno
                    cur = cur.pointed.ai_next
                    continue
                }
                val c = connect(s, cur.pointed.ai_addr, cur.pointed.ai_addrlen)
                if (c == 0) {
                    result = s
                    break
                }
                lastErr = errno
                close(s)
                cur = cur.pointed.ai_next
            }
            freeaddrinfo(res.value)
            if (result < 0) {
                throw SshException("无法连接 $host:$port (errno $lastErr)")
            }
            result
        }
        fd = rc
        memScoped {
            val opt = alloc<IntVar>()
            opt.value = 1
            setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, opt.ptr, sizeOf<IntVar>().toUInt())
        }
    }

    actual fun write(data: ByteArray) {
        var off = 0
        while (off < data.size) {
            val n = memScoped {
                val buf = allocArray<ByteVar>(data.size - off)
                for (i in data.indices) buf[i] = data[i].toByte()
                write(fd, buf, (data.size - off).toULong())
            }
            if (n < 0) throw SshException("发送数据失败 (errno $errno)")
            off += n.toInt()
        }
    }

    actual fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val n = memScoped {
            val buf = allocArray<ByteVar>(length)
            val r = read(fd, buf, length.toULong())
            if (r > 0) {
                for (i in 0 until r.toInt()) buffer[offset + i] = buf[i]
            }
            r
        }
        if (n < 0) throw SshException("读取数据失败 (errno $errno)")
        return n.toInt()
    }

    actual fun close() {
        if (fd >= 0) {
            close(fd)
            fd = -1
        }
    }

    private fun gaiStrerror(code: Int): String {
        val msg = gai_strerror(code)?.toKString() ?: "unknown"
        return msg
    }
}

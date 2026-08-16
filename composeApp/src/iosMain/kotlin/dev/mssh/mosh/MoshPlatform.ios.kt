package dev.mssh.mosh

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.IPPROTO_UDP
import platform.posix.SOCK_DGRAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_RCVTIMEO
import platform.posix.addrinfo
import platform.posix.close
import platform.posix.connect
import platform.posix.freeaddrinfo
import platform.posix.getaddrinfo
import platform.posix.recvfrom
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.socket
import platform.posix.timeval

/** iOS 端 POSIX UDP 实现。 */
@OptIn(ExperimentalForeignApi::class)
internal actual class MoshUdpSocket actual constructor(ip: String, port: Int) {
    private val fd: Int

    init {
        memScoped {
            val resPtr = alloc<CPointerVar<addrinfo>>()
            val rc = getaddrinfo(ip, port.toString(), null, resPtr.ptr)
            require(rc == 0) { "getaddrinfo 失败: $rc" }
            val addr = resPtr.value ?: error("getaddrinfo 无结果")
            try {
                val s = socket(addr.pointed.ai_family, SOCK_DGRAM, IPPROTO_UDP)
                require(s >= 0) { "socket 创建失败" }
                require(connect(s, addr.pointed.ai_addr, addr.pointed.ai_addrlen) == 0) {
                    "UDP connect 失败"
                }
                fd = s
            } finally {
                freeaddrinfo(addr)
            }
        }
    }

    actual fun send(data: ByteArray) {
        data.usePinned { pinned ->
            send(fd, pinned.addressOf(0), data.size.convert(), 0)
        }
    }

    actual fun receive(timeoutMillis: Int): ByteArray? {
        memScoped {
            val tv = alloc<timeval>()
            tv.tv_sec = (timeoutMillis / 1000).convert()
            tv.tv_usec = ((timeoutMillis % 1000) * 1000).convert()
            setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, tv.ptr, sizeOf<timeval>().convert())
            val buf = allocArray<ByteVar>(4096)
            val n = recvfrom(fd, buf, 4096.convert(), 0, null, null)
            if (n <= 0) return null // 超时或错误都按未收到处理
            return ByteArray(n.toInt()) { i -> buf[i] }
        }
    }

    actual fun close() {
        close(fd)
    }
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun zlibCompress(data: ByteArray): ByteArray = memScoped {
    // mosh compressor：compress() 默认级别；目标缓冲留 size + 12.5% + 256 余量
    val cap = data.size + data.size / 8 + 256
    val out = allocArray<ByteVar>(cap)
    val n = data.usePinned { pinned ->
        moshpty.mssh_zlib_compress(pinned.addressOf(0), data.size, out, cap)
    }
    require(n >= 0) { "zlib compress 失败" }
    ByteArray(n) { i -> out[i] }
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun zlibDecompress(data: ByteArray): ByteArray = memScoped {
    // 与 mosh compressor 一致：上限 2MB（终端尺寸上限）
    val cap = 2 * 1024 * 1024
    val out = allocArray<ByteVar>(cap)
    val n = data.usePinned { pinned ->
        moshpty.mssh_zlib_uncompress(pinned.addressOf(0), data.size, out, cap)
    }
    require(n >= 0) { "zlib uncompress 失败" }
    ByteArray(n) { i -> out[i] }
}

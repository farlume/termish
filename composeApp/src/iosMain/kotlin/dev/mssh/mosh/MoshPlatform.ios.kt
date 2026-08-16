package dev.mssh.mosh

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.zlib.Z_DEFAULT_COMPRESSION
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.compress2
import platform.zlib.uncompress
import platform.posix.IPPROTO_UDP
import platform.posix.EMSGSIZE
import platform.posix.SOCK_DGRAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_RCVTIMEO
import platform.posix.addrinfo
import platform.posix.close
import platform.posix.connect
import platform.posix.errno
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
    actual val isIpv6: Boolean

    init {
        memScoped {
            val resPtr = alloc<CPointerVar<addrinfo>>()
            val rc = getaddrinfo(ip, port.toString(), null, resPtr.ptr)
            require(rc == 0) { "getaddrinfo 失败: $rc" }
            val addr = resPtr.value ?: error("getaddrinfo 无结果")
            try {
                isIpv6 = addr.pointed.ai_family == platform.posix.AF_INET6
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

    actual fun send(data: ByteArray): SendResult {
        data.usePinned { pinned ->
            val n = send(fd, pinned.addressOf(0), data.size.convert(), 0)
            if (n.toInt() == data.size) return SendResult.OK
            // 报错时回传 errno：EMSGSIZE 让传输层把 MTU 降到保底值（mosh sendto 语义）
            return if (errno == EMSGSIZE) SendResult.TOO_LARGE else SendResult.FAILED
        }
    }

    actual fun receive(timeoutMillis: Int): UdpDatagram? {
        memScoped {
            val tv = alloc<timeval>()
            tv.tv_sec = (timeoutMillis / 1000).convert()
            tv.tv_usec = ((timeoutMillis % 1000) * 1000).convert()
            setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, tv.ptr, sizeOf<timeval>().convert())
            val buf = allocArray<ByteVar>(4096)
            val n = recvfrom(fd, buf, 4096.convert(), 0, null, null)
            if (n <= 0) return null // 超时或错误都按未收到处理
            return UdpDatagram(ByteArray(n.toInt()) { i -> buf[i] })
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
    val out = allocArray<UByteVar>(cap)
    val destLen = alloc<ULongVar>()
    destLen.value = cap.toULong()
    val rc = data.usePinned { pinned ->
        compress2(out, destLen.ptr, pinned.addressOf(0).reinterpret(), data.size.toULong(), Z_DEFAULT_COMPRESSION)
    }
    require(rc == Z_OK) { "zlib compress 失败 (rc=$rc)" }
    ByteArray(destLen.value.toInt()) { i -> out[i].toByte() }
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun zlibDecompress(data: ByteArray): ByteArray = memScoped {
    // 与 mosh compressor 一致：上限 2MB（终端尺寸上限）
    val cap = 2 * 1024 * 1024
    val out = allocArray<UByteVar>(cap)
    val destLen = alloc<ULongVar>()
    destLen.value = cap.toULong()
    val rc = data.usePinned { pinned ->
        uncompress(out, destLen.ptr, pinned.addressOf(0).reinterpret(), data.size.toULong())
    }
    require(rc == Z_OK || rc == Z_STREAM_END) { "zlib uncompress 失败 (rc=$rc)" }
    ByteArray(destLen.value.toInt()) { i -> out[i].toByte() }
}

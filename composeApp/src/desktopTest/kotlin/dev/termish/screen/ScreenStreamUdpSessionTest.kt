package dev.termish.screen

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 屏幕推流 UDP 漫游会话的回环测试（desktop）：真实 UDP socket，
 * 假 relay 用普通 DatagramSocket 绑随机端口，验证心跳闭环 + 断网续传。
 */
class ScreenStreamUdpSessionTest {
    private val mtu = 400

    @Test
    fun firstPacketIsReloadThenHeartbeats() =
        runBlocking {
            val relay = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
            val session =
                ScreenStreamUdpSession(
                    ip = "127.0.0.1",
                    port = relay.localPort,
                    scope = this,
                    onVideoPacket = {},
                )
            session.start()
            try {
                // 首包应为 reload（重启远端 ffmpeg 重读推流参数）
                val first = receivePacket(relay)
                assertTrue(first != null && first.contentEquals(SCREEN_RELOAD_MAGIC), "首包应为 reload magic")
                // 后续包为普通心跳
                val second = receivePacket(relay)
                // 后续包为带丢帧反馈的心跳（THB\x01 + 1 字节丢帧率，初始 0%）
                assertTrue(
                    second != null && second!!.size == 5 && second!!.contentEquals(heartbeatWithLoss(0)),
                    "后续包应为带反馈的心跳",
                )
            } finally {
                session.close()
                relay.close()
            }
        }

    @Test
    fun heartbeatAndVideoRoundTrip() =
        runBlocking {
            val relay = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
            val received = CopyOnWriteArrayList<ByteArray>()
            val session =
                ScreenStreamUdpSession(
                    ip = "127.0.0.1",
                    port = relay.localPort,
                    scope = this,
                    onVideoPacket = { received.add(it) },
                )
            session.start()
            try {
                // 1. 等手机心跳到达 → 得知手机地址（漫游的地址更新就是靠这个）
                val phoneAddr = receiveHeartbeat(relay)
                assertTrue(phoneAddr != null, "应收到手机心跳包")

                // 2. relay 发视频包（ScreenStreamSender 分片 → 普通 socket 发到手机地址）
                val payload = Random(42).nextBytes(5000)
                sendVideo(relay, phoneAddr!!, payload)

                // 3. 手机应重组出完整视频包
                val got =
                    withTimeoutOrNull(5000) {
                        while (received.isEmpty()) delay(50)
                        received.first()
                    }
                assertTrue(got != null, "应收到重组后的视频包")
                assertContentEquals(payload, got)
            } finally {
                session.close()
                relay.close()
            }
        }

    @Test
    fun roamingSurvivesDisconnectAndResumes() =
        runBlocking {
            val relay = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
            val received = CopyOnWriteArrayList<ByteArray>()
            val session =
                ScreenStreamUdpSession(
                    ip = "127.0.0.1",
                    port = relay.localPort,
                    scope = this,
                    onVideoPacket = { received.add(it) },
                )
            session.start()
            try {
                val phoneAddr = receiveHeartbeat(relay)!!
                relay.soTimeout = 1 // 后面用非阻塞探测

                // 第一批：正常收
                val p1 = Random(7).nextBytes(2000)
                sendVideo(relay, phoneAddr, p1)
                withTimeoutOrNull(5000) { while (received.size < 1) delay(50) }
                assertTrue(received.isNotEmpty(), "断网前应收到第一批")

                // 断网：relay 停止发 3 秒（模拟 WiFi 断开）。会话不得退出
                val before = received.size
                delay(3000)
                assertTrue(session.isActive(), "断网期间会话不应退出")

                // 恢复：relay 继续发（可能换了地址，此处回环地址不变）
                val p2 = Random(9).nextBytes(2000)
                sendVideo(relay, phoneAddr, p2)
                withTimeoutOrNull(5000) { while (received.size <= before) delay(50) }
                assertTrue(received.size > before, "恢复后应继续收到视频（续传）")
                assertContentEquals(p2, received.last())
            } finally {
                session.close()
                relay.close()
            }
        }

    /** 等手机心跳包，返回手机 socket 地址。 */
    private fun receiveHeartbeat(relay: DatagramSocket): java.net.SocketAddress? {
        val buf = ByteArray(64)
        relay.soTimeout = 5000
        val pkt = DatagramPacket(buf, buf.size)
        return try {
            relay.receive(pkt)
            assertTrue(pkt.length >= 4, "心跳包应至少 4 字节")
            pkt.socketAddress
        } catch (_: Exception) {
            null
        }
    }

    /** 收一个包返回其内容（区分 reload / 心跳 magic）。 */
    private fun receivePacket(relay: DatagramSocket): ByteArray? {
        val buf = ByteArray(64)
        relay.soTimeout = 5000
        val pkt = DatagramPacket(buf, buf.size)
        return try {
            relay.receive(pkt)
            pkt.data.copyOf(pkt.length)
        } catch (_: Exception) {
            null
        }
    }

    /** 用 ScreenStreamSender 分片，逐片发到手机地址。 */
    private fun sendVideo(
        relay: DatagramSocket,
        phoneAddr: java.net.SocketAddress,
        payload: ByteArray,
    ) {
        val frags = ArrayList<ByteArray>()
        ScreenStreamSender { frags.add(it) }.send(payload, mtu)
        for (f in frags) {
            relay.send(DatagramPacket(f, f.size, phoneAddr))
        }
    }
}

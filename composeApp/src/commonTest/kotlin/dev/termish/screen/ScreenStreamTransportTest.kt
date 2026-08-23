package dev.termish.screen

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 屏幕推流半可靠分片传输的单测（方案 A 传输原型）：
 * 验证复用 mosh 分片格式后的 round-trip、乱序、重复分片、丢包丢弃、连续多包。
 */
class ScreenStreamTransportTest {
    private val mtu = 400

    @Test
    fun roundTripSinglePacketOutOfOrder() {
        val datagrams = ArrayList<ByteArray>()
        val sender = ScreenStreamSender { datagrams.add(it) }
        val receiver = ScreenStreamReceiver()

        // 固定种子随机数据，避免 zlib 把内容压成单片
        val payload = Random(42).nextBytes(5000)
        sender.send(payload, mtu)
        assertTrue(datagrams.size > 3, "5000B @ mtu=$mtu 应切成多片，实际 ${datagrams.size}")

        var result: ByteArray? = null
        // 乱序喂入：同 id 按 num 放置，应仍能重组
        for (dg in datagrams.reversed()) {
            receiver.onDatagram(dg)?.let { result = it }
        }
        assertNotNull(result, "乱序收齐后应重组出完整包")
        assertContentEquals(payload, result)
    }

    @Test
    fun duplicateFragmentIsIgnored() {
        val datagrams = ArrayList<ByteArray>()
        val sender = ScreenStreamSender { datagrams.add(it) }
        val receiver = ScreenStreamReceiver()

        val payload = Random(7).nextBytes(2000)
        sender.send(payload, mtu)

        // 第一片重复喂两次，其余正常；重复分片应被忽略不破坏重组
        var result: ByteArray? = null
        receiver.onDatagram(datagrams[0])
        receiver.onDatagram(datagrams[0]) // 重复
        for (dg in datagrams.drop(1)) {
            receiver.onDatagram(dg)?.let { result = it }
        }
        assertNotNull(result)
        assertContentEquals(payload, result)
    }

    @Test
    fun droppedMiddleFragmentDiscardsWholePacketAndRecovers() {
        val datagrams = ArrayList<ByteArray>()
        val sender = ScreenStreamSender { datagrams.add(it) }
        val receiver = ScreenStreamReceiver()

        // 第一包：丢中间一片（num=1）→ 永远不完整（半可靠：整包丢弃）
        val p1 = Random(11).nextBytes(3000)
        sender.send(p1, mtu)
        assertTrue(datagrams.size > 3)
        for ((i, dg) in datagrams.withIndex()) {
            if (i == 1) continue // 丢 num=1
            assertNull(receiver.onDatagram(dg), "丢片后不应重组出包")
        }

        // 第二包：同一 sender（id 递增），完整喂入 → 新 id 触发重置，正常重组
        val p2 = Random(13).nextBytes(3000)
        val before = datagrams.size
        sender.send(p2, mtu)
        var result: ByteArray? = null
        for (dg in datagrams.subList(before, datagrams.size)) {
            receiver.onDatagram(dg)?.let { result = it }
        }
        assertNotNull(result, "下一包应能正常重组（丢包自动恢复）")
        assertContentEquals(p2, result)
    }

    @Test
    fun consecutivePacketsReassembleInOrder() {
        val datagrams = ArrayList<ByteArray>()
        val sender = ScreenStreamSender { datagrams.add(it) }
        val receiver = ScreenStreamReceiver()

        val packets = listOf(Random(1).nextBytes(800), Random(2).nextBytes(1500), Random(3).nextBytes(3000))
        val results = ArrayList<ByteArray>()
        for (p in packets) {
            val before = datagrams.size
            sender.send(p, mtu)
            // 逐包喂入（每个包的新 id 边界自然切换）
            for (dg in datagrams.subList(before, datagrams.size)) {
                receiver.onDatagram(dg)?.let { results.add(it) }
            }
        }
        assertEquals(3, results.size, "三个包应各重组一次")
        packets.forEachIndexed { i, p -> assertContentEquals(p, results[i]) }
    }

    @Test
    fun tinyPayloadIsSingleFragment() {
        val datagrams = ArrayList<ByteArray>()
        val sender = ScreenStreamSender { datagrams.add(it) }
        val receiver = ScreenStreamReceiver()

        val payload = byteArrayOf(0x01, 0x02, 0x03)
        sender.send(payload, mtu)
        assertEquals(1, datagrams.size, "小 payload 应是单片")

        val result = receiver.onDatagram(datagrams[0])
        assertNotNull(result)
        assertContentEquals(payload, result)
    }

    @Test
    fun garbageDatagramIsIgnored() {
        val receiver = ScreenStreamReceiver()
        assertNull(receiver.onDatagram(ByteArray(3) { 0x00 }), "非法分片应返回 null 不抛异常")
    }
}

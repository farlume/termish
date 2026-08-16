package dev.mssh.mosh

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SspTest {

    // ---- Proto / Message ----

    @Test
    fun transportInstructionRoundTrip() {
        val inst = TransportInstruction(
            protocolVersion = 2,
            oldNum = 3u,
            newNum = 7u,
            ackNum = 11u,
            throwawayNum = 1u,
            diff = byteArrayOf(1, 2, 3, -1, 0),
            chaff = byteArrayOf(9, 9),
        )
        val parsed = TransportInstruction.parse(inst.serialize())
        assertEquals(2, parsed.protocolVersion)
        assertEquals(3uL, parsed.oldNum)
        assertEquals(7uL, parsed.newNum)
        assertEquals(11uL, parsed.ackNum)
        assertEquals(1uL, parsed.throwawayNum)
        assertContentEquals(inst.diff, parsed.diff)
        assertContentEquals(inst.chaff, parsed.chaff)
    }

    @Test
    fun userMessageKeystrokeMerge() {
        // 相邻 keystroke 合并、resize 独立成条
        val bytes = encodeUserMessage(
            listOf(
                UserEventOut.Keystrokes(byteArrayOf('a'.code.toByte())),
                UserEventOut.Keystrokes(byteArrayOf('b'.code.toByte())),
                UserEventOut.Resize(120, 40),
            ),
        )
        // 直接按 wire 格式解析验证：UserMessage { instruction: 2 条 }
        val r = ProtoReader(bytes)
        var count = 0
        while (r.hasMore) {
            val (f, w) = r.nextTag()!!
            assertEquals(1, f)
            val inst = ProtoReader(r.readBytes())
            val (ef, _) = inst.nextTag()!!
            when (count) {
                0 -> assertEquals(2, ef) // keystroke 扩展
                1 -> assertEquals(3, ef) // resize 扩展
            }
            assertEquals(w, 2)
            count++
        }
        assertEquals(2, count)
    }

    @Test
    fun hostMessageDecode() {
        // 手工构造 HostMessage: instruction { hostbytes { hoststring: "hi" } }, instruction { echoack { 8: 42 } }
        val hb = ProtoWriter()
        hb.bytes(4, "hi".encodeToByteArray())
        val inst1 = ProtoWriter()
        inst1.message(2, hb)
        val ea = ProtoWriter()
        ea.varint(8, 42u)
        val inst2 = ProtoWriter()
        inst2.message(7, ea)
        val outer = ProtoWriter()
        outer.message(1, inst1)
        outer.message(1, inst2)

        val events = decodeHostMessage(outer.toByteArray())
        assertEquals(2, events.size)
        assertContentEquals("hi".encodeToByteArray(), (events[0] as HostEventIn.HostBytes).bytes)
        assertEquals(42uL, (events[1] as HostEventIn.EchoAck).echoAckNum)
    }

    // ---- UserStream ----

    @Test
    fun userStreamDiffAndSubtract() {
        val base = UserStream()
        "ls -l\n".encodeToByteArray().forEach { base.pushByte(it) }
        val cur = base.copy()
        cur.pushByte('x'.code.toByte())
        cur.pushResize(100, 30)

        val diff = cur.diffFrom(base)
        assertTrue(diff.isNotEmpty())
        // 空 diff：同状态
        assertEquals(0, cur.diffFrom(cur).size)

        // subtract 后 cur 只剩后缀
        cur.subtract(base)
        val diff2 = cur.diffFrom(UserStream())
        assertContentEquals(diff, diff2)
    }

    // ---- Fragmentation ----

    @Test
    fun fragmentRoundTripAndAssembly() {
        val fragmenter = Fragmenter()
        val big = TransportInstruction(
            oldNum = 0u,
            newNum = 1u,
            ackNum = 0u,
            throwawayNum = 0u,
            diff = kotlin.random.Random(42).nextBytes(5000), // 固定种子随机数据，防 zlib 压缩成单片
        )
        val frags = fragmenter.makeFragments(big, 400)
        assertTrue(frags.size > 3)
        val asm = FragmentAssembly()
        var done = false
        // 打乱顺序也应能重组（同 id 按 num 放置）
        for (f in frags.reversed()) {
            done = asm.addFragment(f)
        }
        assertTrue(done)
        val restored = asm.assembly()
        assertEquals(1uL, restored.newNum)
        assertContentEquals(big.diff, restored.diff)
    }

    @Test
    fun fragmenterIncrementsIdOnHeaderChange() {
        val f = Fragmenter()
        val a = TransportInstruction(oldNum = 0u, newNum = 1u)
        val id1 = f.makeFragments(a, 400).first().id
        val id2 = f.makeFragments(a, 400).first().id // 同 header → 同 id
        assertEquals(id1, id2)
        val b = TransportInstruction(oldNum = 0u, newNum = 2u)
        val id3 = f.makeFragments(b, 400).first().id
        assertTrue(id3 > id1)
    }

    // ---- Crypto session ----

    @Test
    fun cryptoSessionRoundTrip() {
        val key = dev.mssh.util.base64Encode(ByteArray(16) { it.toByte() })
        val sender = MoshCryptoSession(key)
        val receiver = MoshCryptoSession(key)
        val payload = "fragment-bytes".encodeToByteArray()
        val dg = sender.encrypt(5uL, 1234, 5678, payload)
        // 线上格式：8B seq || 密文 || 16B tag
        assertEquals(5, dg[7].toInt())
        val pkt = receiver.decrypt(dg)
        assertNotNull(pkt)
        assertEquals(5uL, pkt.seq)
        assertEquals(1234, pkt.timestamp)
        assertEquals(5678, pkt.timestampReply)
        assertContentEquals(payload, pkt.payload)
    }

    @Test
    fun cryptoSessionRejectsTamper() {
        val key = dev.mssh.util.base64Encode(ByteArray(16) { (it * 3).toByte() })
        val s = MoshCryptoSession(key)
        val dg = s.encrypt(0uL, 0, 0xffff, byteArrayOf(1, 2, 3))
        dg[10] = (dg[10].toInt() xor 0x40).toByte()
        assertNull(s.decrypt(dg))
        assertNull(s.decrypt(dg.copyOf(10))) // 截断
    }
}

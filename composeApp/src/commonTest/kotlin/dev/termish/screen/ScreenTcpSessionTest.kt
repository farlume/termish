package dev.termish.screen

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ScreenTcpSessionTest {
    @Test
    fun `parser handles split status headers and sticky frames`() {
        val statuses = mutableListOf<Int>()
        val frames = mutableListOf<ByteArray>()
        val parser = ScreenTcpFrameParser(statuses::add, frames::add)
        val first = byteArrayOf(0, 0, 1, 0x67, 1, 2, 3)
        val second = ByteArray(130_000) { (it * 31).toByte() }
        val stream = byteArrayOf('T'.code.toByte(), 'H'.code.toByte(), 'S'.code.toByte(), '1'.code.toByte(), 2) + frame(first) + frame(second)

        // 一字节一推覆盖状态头/长度头/载荷全部跨 read；尾部一次推覆盖粘包。
        stream.copyOfRange(0, 17).forEach { parser.push(byteArrayOf(it)) }
        parser.push(stream.copyOfRange(17, stream.size))

        assertEquals(listOf(2), statuses)
        assertEquals(2, frames.size)
        assertContentEquals(first, frames[0])
        assertContentEquals(second, frames[1])
    }

    @Test
    fun `parser rejects missing status magic and oversized frames`() {
        val parser = ScreenTcpFrameParser({}, {})
        assertFailsWith<IllegalArgumentException> {
            parser.push(byteArrayOf('B'.code.toByte(), 'A'.code.toByte(), 'D'.code.toByte(), '!'.code.toByte(), 0))
        }

        val oversized = ScreenTcpFrameParser({}, {})
        assertFailsWith<IllegalArgumentException> {
            oversized.push(
                byteArrayOf('T'.code.toByte(), 'H'.code.toByte(), 'S'.code.toByte(), '1'.code.toByte(), 0) +
                    intBytes(SCREEN_TCP_MAX_FRAME_BYTES + 1),
            )
        }
    }

    @Test
    fun `busy status is distinct from control capability statuses`() {
        val statuses = mutableListOf<Int>()
        val parser = ScreenTcpFrameParser(statuses::add, {})

        parser.push(byteArrayOf('T'.code.toByte(), 'H'.code.toByte(), 'S'.code.toByte(), '1'.code.toByte(), SCREEN_TCP_STATUS_BUSY.toByte()))

        assertEquals(listOf(SCREEN_TCP_STATUS_BUSY), statuses)
    }

    @Test
    fun `control messages carry an explicit tcp length prefix`() {
        val payload = ScreenControlPacket.encodeText("中文 input")
        val framed = frameScreenTcpControl(payload)

        assertEquals(payload.size, readInt(framed))
        assertContentEquals(payload, framed.copyOfRange(4, framed.size))
    }

    @Test
    fun `auth handshake is length framed and validates a 256 bit token`() {
        val token = "a1".repeat(32)
        val framed = frameScreenTcpAuth(token)

        assertEquals(4 + SCREEN_AUTH_TOKEN_HEX_LENGTH, readInt(framed))
        assertContentEquals("THA1$token".encodeToByteArray(), framed.copyOfRange(4, framed.size))
        assertFailsWith<IllegalArgumentException> { frameScreenTcpAuth("short") }
        assertFailsWith<IllegalArgumentException> { frameScreenTcpAuth("z".repeat(SCREEN_AUTH_TOKEN_HEX_LENGTH)) }
    }

    @Test
    fun `keyboard modifier bitmask is not clamped as a pointer coordinate`() {
        val mods = ScreenControlPacket.MOD_SHIFT or ScreenControlPacket.MOD_CONTROL
        val packet = ScreenControlPacket.encodeKey(ScreenControlPacket.KeyCode.C, mods)
        val bits = readInt(packet.copyOfRange(5, 9))

        assertEquals(mods.toFloat(), Float.fromBits(bits))
    }

    @Test
    fun `virtual mouse atomic click has a distinct control type`() {
        val left = ScreenControlPacket.encode(ScreenControlPacket.TYPE_CLICK, 0.25f, 0.75f)
        val right = ScreenControlPacket.encode(ScreenControlPacket.TYPE_RIGHT_CLICK, 0.25f, 0.75f)

        assertEquals(ScreenControlPacket.TYPE_CLICK, left[4].toInt())
        assertEquals(ScreenControlPacket.TYPE_RIGHT_CLICK, right[4].toInt())
    }

    @Test
    fun `virtual mouse drag sequence has distinct control types`() {
        val down = ScreenControlPacket.encode(ScreenControlPacket.TYPE_VIRTUAL_LEFT_DOWN, 0.25f, 0.75f)
        val drag = ScreenControlPacket.encode(ScreenControlPacket.TYPE_VIRTUAL_LEFT_DRAG, 0.5f, 0.5f)
        val up = ScreenControlPacket.encode(ScreenControlPacket.TYPE_VIRTUAL_LEFT_UP, 0.75f, 0.25f)

        assertEquals(ScreenControlPacket.TYPE_VIRTUAL_LEFT_DOWN, down[4].toInt())
        assertEquals(ScreenControlPacket.TYPE_VIRTUAL_LEFT_DRAG, drag[4].toInt())
        assertEquals(ScreenControlPacket.TYPE_VIRTUAL_LEFT_UP, up[4].toInt())
    }

    @Test
    fun `tcp lease heartbeat is a framed no input control packet`() {
        val heartbeat = ScreenControlPacket.encode(ScreenControlPacket.TYPE_HEARTBEAT, 0f, 0f)
        val framed = frameScreenTcpControl(heartbeat)

        assertEquals(17, heartbeat.size)
        assertEquals(ScreenControlPacket.TYPE_HEARTBEAT, heartbeat[4].toInt())
        assertContentEquals(heartbeat, framed.copyOfRange(4, framed.size))
    }

    private fun frame(payload: ByteArray): ByteArray = intBytes(payload.size) + payload

    private fun intBytes(value: Int): ByteArray =
        byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte(),
        )

    private fun readInt(data: ByteArray): Int =
        ((data[0].toInt() and 0xff) shl 24) or
            ((data[1].toInt() and 0xff) shl 16) or
            ((data[2].toInt() and 0xff) shl 8) or
            (data[3].toInt() and 0xff)
}

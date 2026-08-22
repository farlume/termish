package dev.termish.screen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class H264StreamTest {
    private fun sc3(vararg b: Int) = byteArrayOf(0, 0, 1, *b.map { it.toByte() }.toByteArray())

    @Test
    fun `parser splits nal units by start code`() {
        val parser = H264Stream.AnnexBParser()
        val sps = sc3(0x67, 0x42, 0x00) // type 7
        val pps = sc3(0x68, 0xce, 0x3c) // type 8
        val idr = sc3(0x65, 0x88, 0x84) // type 5
        // 末尾补结束 start code（流式解析需要边界切出最后一个 NAL）
        val stream = sps + pps + idr + byteArrayOf(0, 0, 1)
        val nals = mutableListOf<H264Stream.Nal>()
        for (i in 0 until stream.size step 3) {
            parser.push(stream.copyOfRange(i, (i + 3).coerceAtMost(stream.size)))?.let { nals.add(it) }
        }
        while (true) {
            parser.drain()?.let { nals.add(it) } ?: break
        }
        assertEquals(3, nals.size)
        assertEquals(7, nals[0].type)
        assertEquals(8, nals[1].type)
        assertEquals(5, nals[2].type)
        assertTrue(nals[2].data.contentEquals(byteArrayOf(0x65.toByte(), 0x88.toByte(), 0x84.toByte())))
    }

    @Test
    fun `parser handles 4-byte start code`() {
        val parser = H264Stream.AnnexBParser()
        val nal = byteArrayOf(0, 0, 0, 1, 0x41, 0x9a.toByte(), 0, 0, 1) // type 1 slice + 结束边界
        val out = parser.push(nal)
        assertNotNull(out)
        assertEquals(1, out.type)
        assertTrue(out.data.contentEquals(byteArrayOf(0x41, 0x9a.toByte())))
    }

    @Test
    fun `parser waits for complete nal`() {
        val parser = H264Stream.AnnexBParser()
        // 只给半个 NAL：无结束 start code → null
        assertNull(parser.push(byteArrayOf(0, 0, 1, 0x65.toByte(), 0x88.toByte())))
        // 补上结束边界
        val out = parser.push(byteArrayOf(0, 0, 1))
        assertNotNull(out)
        assertEquals(5, out.type)
    }

    @Test
    fun `parser drops garbage before first start code`() {
        val parser = H264Stream.AnnexBParser()
        val stream = byteArrayOf(9, 9, 0, 0, 1, 0x67, 0x42) + byteArrayOf(0, 0, 1)
        val out = parser.push(stream)
        assertNotNull(out)
        assertEquals(7, out.type)
    }

    @Test
    fun `extractParameterSets finds sps and pps`() {
        val sps = sc3(0x67, 0x42, 0x00, 0x1e)
        val pps = sc3(0x68, 0xce, 0x3c)
        val idr = sc3(0x65, 0x88)
        val stream =
            byteArrayOf(0, 0, 0, 1) + sps.drop(3).toByteArray() +
                byteArrayOf(0, 0, 1) + pps.drop(3).toByteArray() +
                byteArrayOf(0, 0, 1) + idr.drop(3).toByteArray()
        val ps = H264Stream.extractParameterSets(stream)
        assertNotNull(ps.sps)
        assertNotNull(ps.pps)
        // 带 start code 前缀（CSD 格式）
        assertEquals(0, ps.sps!![0].toInt())
        assertEquals(1, ps.sps!![3].toInt())
    }

    @Test
    fun `type names`() {
        assertEquals("SPS", H264Stream.typeName(7))
        assertEquals("IDR", H264Stream.typeName(5))
    }

    @Test
    fun `parseSpsDimensions extracts real 720p sps`() {
        // 真实 libx264 输出（1280x720）：6742c01fda014016ec0440000003004000000f23c60ca8
        val sps =
            byteArrayOf(
                0x67,
                0x42.toByte(),
                0xc0.toByte(),
                0x1f,
                0xda.toByte(),
                0x01,
                0x40,
                0x16,
                0xec.toByte(),
                0x04,
                0x40,
                0x00,
                0x00,
                0x03,
                0x00,
                0x40,
                0x00,
                0x00,
                0x0f,
                0x23,
                0xc6.toByte(),
                0x0c,
                0xa8.toByte(),
            )
        val dims = H264Stream.parseSpsDimensions(sps)
        assertNotNull(dims, "应能解析出宽高")
        assertEquals(1280, dims.first)
        assertEquals(720, dims.second)
    }
}

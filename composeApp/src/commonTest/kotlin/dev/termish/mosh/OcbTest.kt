package dev.termish.mosh

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

/**
 * 期望值来自独立的 AES-128-OCB 标准测试向量：nonce 96bit / tag 128bit / 无 AD，与线上一致。
 */
class OcbTest {
    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private val key = hex("000102030405060708090A0B0C0D0E0F")

    @Test
    fun emptyPlaintext() {
        val out = Ocb(key).encrypt(hex("BBAA99887766554433221100"), ByteArray(0))
        assertContentEquals(hex("785407BFFFC8AD9EDCC5520AC9111EE6"), out)
    }

    @Test
    fun eightBytes() {
        val out = Ocb(key).encrypt(hex("BBAA99887766554433221101"), hex("0001020304050607"))
        assertContentEquals(hex("6820B3657B6F615AD6E734CE5F69DEA19FC35214F5795FAA"), out)
    }

    @Test
    fun sixteenBytes() {
        val out =
            Ocb(key).encrypt(
                hex("BBAA99887766554433221102"),
                hex("000102030405060708090A0B0C0D0E0F"),
            )
        assertContentEquals(
            hex("C050A7E919AA5643BFF595B66ACC106C92537991AB4B8C84A250F74868833FB8"),
            out,
        )
    }

    @Test
    fun twentyFourBytes() {
        // 一整块 + 8 字节尾部
        val out =
            Ocb(key).encrypt(
                hex("BBAA99887766554433221103"),
                hex("000102030405060708090A0B0C0D0E0F1011121314151617"),
            )
        assertContentEquals(
            hex("1591E0EC9E6FC5A83475F939906EB53E5E93E9CFEEEC495FA4E32618DD97FAD9A80F61C1A055EBA7"),
            out,
        )
    }

    @Test
    fun thirtyThreeBytes() {
        // 两整块 + 1 字节尾部
        val out =
            Ocb(key).encrypt(
                hex("BBAA99887766554433221104"),
                hex("000102030405060708090A0B0C0D0E0F101112131415161718191A1B1C1D1E1F20"),
            )
        assertContentEquals(
            hex(
                "571D535B60B277188BE5147170A9A22CDB9EF96F538354AF0E6E5D7F6F640AF83" +
                    "F30EB393BC18541A8BEB2883475DF7A62",
            ),
            out,
        )
    }

    @Test
    fun decryptRoundTrip() {
        val ocb = Ocb(key)
        val plain = "mosh over kotlin multiplatform 多平台".encodeToByteArray()
        val nonce = hex("BBAA9988776655443322110F")
        val ct = ocb.encrypt(nonce, plain)
        assertContentEquals(plain, ocb.decrypt(nonce, ct))
    }

    /**
     * 独立解密向量：用标准 encrypt 向量反向验证 decrypt。
     * round-trip 只能证明加解密自洽；这里用标准向量输出验证方向也正确。
     */
    @Test
    fun decryptAgainstReferenceVectors() {
        val ocb = Ocb(key)
        assertContentEquals(
            ByteArray(0),
            ocb.decrypt(hex("BBAA99887766554433221100"), hex("785407BFFFC8AD9EDCC5520AC9111EE6")),
        )
        assertContentEquals(
            hex("0001020304050607"),
            ocb.decrypt(hex("BBAA99887766554433221101"), hex("6820B3657B6F615AD6E734CE5F69DEA19FC35214F5795FAA")),
        )
        assertContentEquals(
            hex("000102030405060708090A0B0C0D0E0F"),
            ocb.decrypt(
                hex("BBAA99887766554433221102"),
                hex("C050A7E919AA5643BFF595B66ACC106C92537991AB4B8C84A250F74868833FB8"),
            ),
        )
        assertContentEquals(
            hex("000102030405060708090A0B0C0D0E0F101112131415161718191A1B1C1D1E1F20"),
            ocb.decrypt(
                hex("BBAA99887766554433221104"),
                hex(
                    "571D535B60B277188BE5147170A9A22CDB9EF96F538354AF0E6E5D7F6F640AF83" +
                        "F30EB393BC18541A8BEB2883475DF7A62",
                ),
            ),
        )
    }

    @Test
    fun decryptRejectsCorruptedTag() {
        val ocb = Ocb(key)
        val nonce = hex("BBAA9988776655443322110E")
        val ct = ocb.encrypt(nonce, "hello".encodeToByteArray())
        ct[ct.size - 1] = (ct[ct.size - 1].toInt() xor 1).toByte()
        assertNull(ocb.decrypt(nonce, ct))
    }
}

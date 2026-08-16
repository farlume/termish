package dev.termish.mosh

/**
 * AES-128 单块加解密（纯 Kotlin，供 OCB 使用）。
 *
 * 只实现 ECB 单块原语；模式层见 [Ocb]。标准 FIPS-197 查表实现，
 * 密钥扩展在构造时完成。
 */
internal class Aes128(key: ByteArray) {
    init {
        require(key.size == 16) { "AES-128 key must be 16 bytes" }
    }

    /** 11 个轮密钥 × 16 字节。 */
    private val rk = ByteArray(176)

    init {
        key.copyInto(rk, 0, 0, 16)
        var bytesGenerated = 16
        var rconIter = 1
        val temp = ByteArray(4)
        while (bytesGenerated < 176) {
            for (i in 0..3) temp[i] = rk[bytesGenerated - 4 + i]
            if (bytesGenerated % 16 == 0) {
                // RotWord + SubWord + Rcon
                val t = temp[0]
                temp[0] = (SBOX[temp[1].toInt() and 0xff] xor RCON[rconIter]).toByte()
                temp[1] = SBOX[temp[2].toInt() and 0xff].toByte()
                temp[2] = SBOX[temp[3].toInt() and 0xff].toByte()
                temp[3] = SBOX[t.toInt() and 0xff].toByte()
                rconIter++
            }
            for (i in 0..3) {
                rk[bytesGenerated] = (rk[bytesGenerated - 16].toInt() xor temp[i].toInt()).toByte()
                bytesGenerated++
            }
        }
    }

    fun encryptBlock(input: ByteArray, inOff: Int, output: ByteArray, outOff: Int) {
        val s = ByteArray(16)
        for (i in 0..15) s[i] = (input[inOff + i].toInt() xor rk[i].toInt()).toByte()
        var round = 1
        while (round < 10) {
            subBytesShiftRows(s)
            mixColumns(s)
            addRoundKey(s, round)
            round++
        }
        subBytesShiftRows(s)
        addRoundKey(s, 10)
        for (i in 0..15) output[outOff + i] = s[i]
    }

    fun decryptBlock(input: ByteArray, inOff: Int, output: ByteArray, outOff: Int) {
        val s = ByteArray(16)
        for (i in 0..15) s[i] = (input[inOff + i].toInt() xor rk[160 + i].toInt()).toByte()
        var round = 9
        while (round >= 1) {
            invShiftRows(s)
            invSubBytes(s)
            addRoundKey(s, round)
            invMixColumns(s)
            round--
        }
        invShiftRows(s)
        invSubBytes(s)
        addRoundKey(s, 0)
        for (i in 0..15) output[outOff + i] = s[i]
    }

    private fun addRoundKey(s: ByteArray, round: Int) {
        val off = round * 16
        for (i in 0..15) s[i] = (s[i].toInt() xor rk[off + i].toInt()).toByte()
    }

    private fun subBytesShiftRows(s: ByteArray) {
        // 状态按列主序存放：s[col*4+row]。ShiftRows 等价于按目标下标整体置换。
        val t = s.copyOf()
        for (r in 0..3) {
            for (c in 0..3) {
                s[c * 4 + r] = SBOX[t[((c + r) and 3) * 4 + r].toInt() and 0xff].toByte()
            }
        }
    }

    private fun invSubBytes(s: ByteArray) {
        for (i in 0..15) s[i] = INV_SBOX[s[i].toInt() and 0xff].toByte()
    }

    private fun invShiftRows(s: ByteArray) {
        val t = s.copyOf()
        for (r in 0..3) {
            for (c in 0..3) {
                s[c * 4 + r] = t[((c - r) and 3) * 4 + r]
            }
        }
    }

    private fun mixColumns(s: ByteArray) {
        for (c in 0..3) {
            val i = c * 4
            val a0 = s[i].toInt() and 0xff
            val a1 = s[i + 1].toInt() and 0xff
            val a2 = s[i + 2].toInt() and 0xff
            val a3 = s[i + 3].toInt() and 0xff
            s[i] = (xtime(a0) xor (xtime(a1) xor a1) xor a2 xor a3).toByte()
            s[i + 1] = (a0 xor xtime(a1) xor (xtime(a2) xor a2) xor a3).toByte()
            s[i + 2] = (a0 xor a1 xor xtime(a2) xor (xtime(a3) xor a3)).toByte()
            s[i + 3] = ((xtime(a0) xor a0) xor a1 xor a2 xor xtime(a3)).toByte()
        }
    }

    private fun invMixColumns(s: ByteArray) {
        for (c in 0..3) {
            val i = c * 4
            val a0 = s[i].toInt() and 0xff
            val a1 = s[i + 1].toInt() and 0xff
            val a2 = s[i + 2].toInt() and 0xff
            val a3 = s[i + 3].toInt() and 0xff
            s[i] = (mul(0x0e, a0) xor mul(0x0b, a1) xor mul(0x0d, a2) xor mul(0x09, a3)).toByte()
            s[i + 1] = (mul(0x09, a0) xor mul(0x0e, a1) xor mul(0x0b, a2) xor mul(0x0d, a3)).toByte()
            s[i + 2] = (mul(0x0d, a0) xor mul(0x09, a1) xor mul(0x0e, a2) xor mul(0x0b, a3)).toByte()
            s[i + 3] = (mul(0x0b, a0) xor mul(0x0d, a1) xor mul(0x09, a2) xor mul(0x0e, a3)).toByte()
        }
    }

    private fun xtime(x: Int): Int = ((x shl 1) xor (if (x and 0x80 != 0) 0x1b else 0)) and 0xff

    private fun mul(a: Int, b: Int): Int {
        var x = a
        var y = b
        var r = 0
        while (y != 0) {
            if (y and 1 != 0) r = r xor x
            x = xtime(x)
            y = y ushr 1
        }
        return r
    }

    companion object {
        private val SBOX = intArrayOf(
            0x63, 0x7c, 0x77, 0x7b, 0xf2, 0x6b, 0x6f, 0xc5, 0x30, 0x01, 0x67, 0x2b, 0xfe, 0xd7, 0xab, 0x76,
            0xca, 0x82, 0xc9, 0x7d, 0xfa, 0x59, 0x47, 0xf0, 0xad, 0xd4, 0xa2, 0xaf, 0x9c, 0xa4, 0x72, 0xc0,
            0xb7, 0xfd, 0x93, 0x26, 0x36, 0x3f, 0xf7, 0xcc, 0x34, 0xa5, 0xe5, 0xf1, 0x71, 0xd8, 0x31, 0x15,
            0x04, 0xc7, 0x23, 0xc3, 0x18, 0x96, 0x05, 0x9a, 0x07, 0x12, 0x80, 0xe2, 0xeb, 0x27, 0xb2, 0x75,
            0x09, 0x83, 0x2c, 0x1a, 0x1b, 0x6e, 0x5a, 0xa0, 0x52, 0x3b, 0xd6, 0xb3, 0x29, 0xe3, 0x2f, 0x84,
            0x53, 0xd1, 0x00, 0xed, 0x20, 0xfc, 0xb1, 0x5b, 0x6a, 0xcb, 0xbe, 0x39, 0x4a, 0x4c, 0x58, 0xcf,
            0xd0, 0xef, 0xaa, 0xfb, 0x43, 0x4d, 0x33, 0x85, 0x45, 0xf9, 0x02, 0x7f, 0x50, 0x3c, 0x9f, 0xa8,
            0x51, 0xa3, 0x40, 0x8f, 0x92, 0x9d, 0x38, 0xf5, 0xbc, 0xb6, 0xda, 0x21, 0x10, 0xff, 0xf3, 0xd2,
            0xcd, 0x0c, 0x13, 0xec, 0x5f, 0x97, 0x44, 0x17, 0xc4, 0xa7, 0x7e, 0x3d, 0x64, 0x5d, 0x19, 0x73,
            0x60, 0x81, 0x4f, 0xdc, 0x22, 0x2a, 0x90, 0x88, 0x46, 0xee, 0xb8, 0x14, 0xde, 0x5e, 0x0b, 0xdb,
            0xe0, 0x32, 0x3a, 0x0a, 0x49, 0x06, 0x24, 0x5c, 0xc2, 0xd3, 0xac, 0x62, 0x91, 0x95, 0xe4, 0x79,
            0xe7, 0xc8, 0x37, 0x6d, 0x8d, 0xd5, 0x4e, 0xa9, 0x6c, 0x56, 0xf4, 0xea, 0x65, 0x7a, 0xae, 0x08,
            0xba, 0x78, 0x25, 0x2e, 0x1c, 0xa6, 0xb4, 0xc6, 0xe8, 0xdd, 0x74, 0x1f, 0x4b, 0xbd, 0x8b, 0x8a,
            0x70, 0x3e, 0xb5, 0x66, 0x48, 0x03, 0xf6, 0x0e, 0x61, 0x35, 0x57, 0xb9, 0x86, 0xc1, 0x1d, 0x9e,
            0xe1, 0xf8, 0x98, 0x11, 0x69, 0xd9, 0x8e, 0x94, 0x9b, 0x1e, 0x87, 0xe9, 0xce, 0x55, 0x28, 0xdf,
            0x8c, 0xa1, 0x89, 0x0d, 0xbf, 0xe6, 0x42, 0x68, 0x41, 0x99, 0x2d, 0x0f, 0xb0, 0x54, 0xbb, 0x16,
        )

        private val INV_SBOX = IntArray(256).also { inv ->
            for (i in 0..255) inv[SBOX[i]] = i
        }

        private val RCON = intArrayOf(0x00, 0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x1b, 0x36)
    }
}

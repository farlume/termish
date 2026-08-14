package dev.mssh.crypto

/**
 * Poly1305 (RFC 8439) — pure Kotlin, 26-bit limbs.
 */
object Poly1305 {

    private val MASK26 = (1L shl 26) - 1

    fun mac(key: ByteArray, data: ByteArray): ByteArray {
        require(key.size == 32)
        // r = key[0..16] with clamped bits; s = key[16..32]
        var r0 = le32(key, 0).toLong() and 0x3ffffffL
        var r1 = (((le32(key, 3) ushr 2) or (le32(key, 4) shl 6)).toLong()) and 0x3ffff03L
        var r2 = (((le32(key, 6) ushr 4) or (le32(key, 7) shl 4)).toLong()) and 0x3ffc0ffL
        var r3 = (((le32(key, 9) ushr 6) or (le32(key, 10) shl 2)).toLong()) and 0x3f03fffL
        var r4 = (le32(key, 12) ushr 8).toLong() and 0x00fffffL
        val s1 = r1 * 5; val s2 = r2 * 5; val s3 = r3 * 5; val s4 = r4 * 5

        var h0 = 0L; var h1 = 0L; var h2 = 0L; var h3 = 0L; var h4 = 0L

        var i = 0
        while (i < data.size) {
            // read 16-byte block (padded with 1)
            var b0 = 0L; var b1 = 0L; var b2 = 0L; var b3 = 0L; var b4 = 0L
            val rem = data.size - i
            if (rem >= 16) {
                b0 = le32(data, i).toLong() and 0x3ffffffL
                b1 = (((le32(data, i + 3) ushr 2) or (le32(data, i + 4) shl 6)).toLong()) and 0x3ffffffL
                b2 = (((le32(data, i + 6) ushr 4) or (le32(data, i + 7) shl 4)).toLong()) and 0x3ffffffL
                b3 = (((le32(data, i + 9) ushr 6) or (le32(data, i + 10) shl 2)).toLong()) and 0x3ffffffL
                b4 = (le32(data, i + 12) ushr 8).toLong() and 0x3ffffffL
                // full block: the implicit 0x01 byte sits at bit 128
                b4 += 1L shl 24
            } else {
                val tmp = ByteArray(16)
                data.copyInto(tmp, 0, i, data.size)
                tmp[rem] = 1
                b0 = le32(tmp, 0).toLong() and 0x3ffffffL
                b1 = (((le32(tmp, 3) ushr 2) or (le32(tmp, 4) shl 6)).toLong()) and 0x3ffffffL
                b2 = (((le32(tmp, 6) ushr 4) or (le32(tmp, 7) shl 4)).toLong()) and 0x3ffffffL
                b3 = (((le32(tmp, 9) ushr 6) or (le32(tmp, 10) shl 2)).toLong()) and 0x3ffffffL
                b4 = (le32(tmp, 12) ushr 8).toLong() and 0x3ffffffL
            }

            // h += block
            h0 += b0; h1 += b1; h2 += b2; h3 += b3; h4 += b4

            // h *= r  (schoolbook, 5x5)
            var d0 = h0 * r0 + h1 * s4 + h2 * s3 + h3 * s2 + h4 * s1
            var d1 = h0 * r1 + h1 * r0 + h2 * s4 + h3 * s3 + h4 * s2
            var d2 = h0 * r2 + h1 * r1 + h2 * r0 + h3 * s4 + h4 * s3
            var d3 = h0 * r3 + h1 * r2 + h2 * r1 + h3 * r0 + h4 * s4
            var d4 = h0 * r4 + h1 * r3 + h2 * r2 + h3 * r1 + h4 * r0

            // partial carry
            var c = d0 shr 26; h0 = d0 and MASK26
            d1 += c; c = d1 shr 26; h1 = d1 and MASK26
            d2 += c; c = d2 shr 26; h2 = d2 and MASK26
            d3 += c; c = d3 shr 26; h3 = d3 and MASK26
            d4 += c; c = d4 shr 26; h4 = d4 and MASK26
            h0 += c * 5; c = h0 shr 26; h0 = h0 and MASK26
            h1 += c

            i += 16
        }

        // full carry
        var c = h1 shr 26; h1 = h1 and MASK26; h2 += c
        c = h2 shr 26; h2 = h2 and MASK26; h3 += c
        c = h3 shr 26; h3 = h3 and MASK26; h4 += c
        c = h4 shr 26; h4 = h4 and MASK26; h0 += c * 5
        c = h0 shr 26; h0 = h0 and MASK26; h1 += c

        // h = h mod (2^130-5): if h + 5 overflows limb 4, h >= p -> use g
        var g0 = h0 + 5; c = g0 shr 26; g0 = g0 and MASK26
        var g1 = h1 + c; c = g1 shr 26; g1 = g1 and MASK26
        var g2 = h2 + c; c = g2 shr 26; g2 = g2 and MASK26
        var g3 = h3 + c; c = g3 shr 26; g3 = g3 and MASK26
        var g4 = h4 + c - (1L shl 26)
        if (g4 >= 0) {
            h0 = g0; h1 = g1; h2 = g2; h3 = g3; h4 = g4
        }

        // h = (h + s) mod 2^128 — each 32-bit word must be truncated before
        // adding s so carries are computed on the true word values.
        var carry = 0L
        val w0 = (h0 or (h1 shl 26)) and 0xffffffffL
        val f0 = w0 + (le32(key, 16).toLong() and 0xffffffffL)
        carry = f0 shr 32
        val w1 = ((h1 ushr 6) or (h2 shl 20)) and 0xffffffffL
        val f1 = w1 + (le32(key, 20).toLong() and 0xffffffffL) + carry
        carry = f1 shr 32
        val w2 = ((h2 ushr 12) or (h3 shl 14)) and 0xffffffffL
        val f2 = w2 + (le32(key, 24).toLong() and 0xffffffffL) + carry
        carry = f2 shr 32
        val w3 = ((h3 ushr 18) or (h4 shl 8)) and 0xffffffffL
        val f3 = w3 + (le32(key, 28).toLong() and 0xffffffffL) + carry

        val out = ByteArray(16)
        putLe32(out, 0, f0.toInt())
        putLe32(out, 4, f1.toInt())
        putLe32(out, 8, f2.toInt())
        putLe32(out, 12, f3.toInt())
        return out
    }

    private fun le32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or
                ((b[off + 1].toInt() and 0xff) shl 8) or
                ((b[off + 2].toInt() and 0xff) shl 16) or
                ((b[off + 3].toInt() and 0xff) shl 24)

    private fun putLe32(out: ByteArray, off: Int, v: Int) {
        out[off] = v.toByte()
        out[off + 1] = (v shr 8).toByte()
        out[off + 2] = (v shr 16).toByte()
        out[off + 3] = (v shr 24).toByte()
    }
}

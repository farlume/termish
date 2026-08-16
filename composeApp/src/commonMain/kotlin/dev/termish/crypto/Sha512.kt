package dev.termish.crypto

/**
 * SHA-512 (FIPS 180-4) — pure Kotlin, needed by Ed25519.
 */
object Sha512 {

    private val K = ulongArrayOf(
        0x428a2f98d728ae22uL, 0x7137449123ef65cduL, 0xb5c0fbcfec4d3b2fuL, 0xe9b5dba58189dbbcuL,
        0x3956c25bf348b538uL, 0x59f111f1b605d019uL, 0x923f82a4af194f9buL, 0xab1c5ed5da6d8118uL,
        0xd807aa98a3030242uL, 0x12835b0145706fbeuL, 0x243185be4ee4b28cuL, 0x550c7dc3d5ffb4e2uL,
        0x72be5d74f27b896fuL, 0x80deb1fe3b1696b1uL, 0x9bdc06a725c71235uL, 0xc19bf174cf692694uL,
        0xe49b69c19ef14ad2uL, 0xefbe4786384f25e3uL, 0x0fc19dc68b8cd5b5uL, 0x240ca1cc77ac9c65uL,
        0x2de92c6f592b0275uL, 0x4a7484aa6ea6e483uL, 0x5cb0a9dcbd41fbd4uL, 0x76f988da831153b5uL,
        0x983e5152ee66dfabuL, 0xa831c66d2db43210uL, 0xb00327c898fb213fuL, 0xbf597fc7beef0ee4uL,
        0xc6e00bf33da88fc2uL, 0xd5a79147930aa725uL, 0x06ca6351e003826fuL, 0x142929670a0e6e70uL,
        0x27b70a8546d22ffcuL, 0x2e1b21385c26c926uL, 0x4d2c6dfc5ac42aeduL, 0x53380d139d95b3dfuL,
        0x650a73548baf63deuL, 0x766a0abb3c77b2a8uL, 0x81c2c92e47edaee6uL, 0x92722c851482353buL,
        0xa2bfe8a14cf10364uL, 0xa81a664bbc423001uL, 0xc24b8b70d0f89791uL, 0xc76c51a30654be30uL,
        0xd192e819d6ef5218uL, 0xd69906245565a910uL, 0xf40e35855771202auL, 0x106aa07032bbd1b8uL,
        0x19a4c116b8d2d0c8uL, 0x1e376c085141ab53uL, 0x2748774cdf8eeb99uL, 0x34b0bcb5e19b48a8uL,
        0x391c0cb3c5c95a63uL, 0x4ed8aa4ae3418acbuL, 0x5b9cca4f7763e373uL, 0x682e6ff3d6b2b8a3uL,
        0x748f82ee5defb2fcuL, 0x78a5636f43172f60uL, 0x84c87814a1f0ab72uL, 0x8cc702081a6439ecuL,
        0x90befffa23631e28uL, 0xa4506cebde82bde9uL, 0xbef9a3f7b2c67915uL, 0xc67178f2e372532buL,
        0xca273eceea26619cuL, 0xd186b8c721c0c207uL, 0xeada7dd6cde0eb1euL, 0xf57d4f7fee6ed178uL,
        0x06f067aa72176fbauL, 0x0a637dc5a2c898a6uL, 0x113f9804bef90daeuL, 0x1b710b35131c471buL,
        0x28db77f523047d84uL, 0x32caab7b40c72493uL, 0x3c9ebe0a15c9bebcuL, 0x431d67c49c100d4cuL,
        0x4cc5d4becb3e42b6uL, 0x597f299cfc657e2auL, 0x5fcb6fab3ad6faecuL, 0x6c44198c4a475817uL,
    )

    fun digest(data: ByteArray): ByteArray {
        val h = ulongArrayOf(
            0x6a09e667f3bcc908uL, 0xbb67ae8584caa73buL, 0x3c6ef372fe94f82buL, 0xa54ff53a5f1d36f1uL,
            0x510e527fade682d1uL, 0x9b05688c2b3e6c1fuL, 0x1f83d9abfb41bd6buL, 0x5be0cd19137e2179uL,
        )
        val msg = pad(data)
        val w = ULongArray(80)
        var i = 0
        while (i < msg.size) {
            for (t in 0 until 16) {
                var v = 0uL
                for (b in 0 until 8) v = (v shl 8) or (msg[i + t * 8 + b].toULong() and 0xffuL)
                w[t] = v
            }
            for (t in 16 until 80) {
                val s0 = rotr(w[t - 15], 1) xor rotr(w[t - 15], 8) xor (w[t - 15] shr 7)
                val s1 = rotr(w[t - 2], 19) xor rotr(w[t - 2], 61) xor (w[t - 2] shr 6)
                w[t] = w[t - 16] + s0 + w[t - 7] + s1
            }
            var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]
            var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]
            for (t in 0 until 80) {
                val s1 = rotr(e, 14) xor rotr(e, 18) xor rotr(e, 41)
                val ch = (e and f) xor (e.inv() and g)
                val t1 = hh + s1 + ch + K[t] + w[t]
                val s0 = rotr(a, 28) xor rotr(a, 34) xor rotr(a, 39)
                val maj = (a and b) xor (a and c) xor (b and c)
                val t2 = s0 + maj
                hh = g; g = f; f = e; e = d + t1
                d = c; c = b; b = a; a = t1 + t2
            }
            h[0] += a; h[1] += b; h[2] += c; h[3] += d
            h[4] += e; h[5] += f; h[6] += g; h[7] += hh
            i += 128
        }
        val out = ByteArray(64)
        for (j in 0 until 8) {
            var v = h[j]
            for (b in 7 downTo 0) {
                out[j * 8 + b] = (v and 0xffuL).toByte()
                v = v shr 8
            }
        }
        return out
    }

    private fun pad(data: ByteArray): ByteArray {
        val bitLen = data.size.toLong() * 8
        val padLen = (128 - (data.size + 17) % 128) % 128
        val out = ByteArray(data.size + 1 + padLen + 16)
        data.copyInto(out)
        out[data.size] = 0x80.toByte()
        for (i in 0 until 8) {
            // big-endian 128-bit length; top 64 bits are zero for any realistic input
            out[out.size - 1 - i] = (bitLen shr (8 * i)).toByte()
        }
        return out
    }

    private fun rotr(x: ULong, n: Int): ULong = (x shr n) or (x shl (64 - n))
}

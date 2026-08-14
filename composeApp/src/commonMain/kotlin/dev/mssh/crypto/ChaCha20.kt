package dev.mssh.crypto

/**
 * ChaCha20 (RFC 8439) — pure Kotlin.
 */
object ChaCha20 {

    private const val ROUNDS = 20

    fun block(key: ByteArray, counter: Long, nonce: ByteArray): ByteArray {
        require(key.size == 32 && nonce.size == 12)
        val state = IntArray(16)
        state[0] = 0x61707865
        state[1] = 0x3320646e
        state[2] = 0x79622d32
        state[3] = 0x6b206574
        for (i in 0 until 8) state[4 + i] = le32(key, i * 4)
        // RFC 8439 IETF layout: 32-bit counter in word 12, 96-bit nonce in words 13-15
        state[12] = (counter and 0xffffffffL).toInt()
        state[13] = le32(nonce, 0)
        state[14] = le32(nonce, 4)
        state[15] = le32(nonce, 8)
        val x = state.copyOf()
        for (i in 0 until ROUNDS / 2) {
            qr(x, 0, 4, 8, 12)
            qr(x, 1, 5, 9, 13)
            qr(x, 2, 6, 10, 14)
            qr(x, 3, 7, 11, 15)
            qr(x, 0, 5, 10, 15)
            qr(x, 1, 6, 11, 12)
            qr(x, 2, 7, 8, 13)
            qr(x, 3, 4, 9, 14)
        }
        val out = ByteArray(64)
        for (i in 0 until 16) {
            putLe32(out, i * 4, x[i] + state[i])
        }
        return out
    }

    private fun qr(x: IntArray, a: Int, b: Int, c: Int, d: Int) {
        x[a] += x[b]; x[d] = rotl(x[d] xor x[a], 16)
        x[c] += x[d]; x[b] = rotl(x[b] xor x[c], 12)
        x[a] += x[b]; x[d] = rotl(x[d] xor x[a], 8)
        x[c] += x[d]; x[b] = rotl(x[b] xor x[c], 7)
    }

    private fun rotl(v: Int, n: Int): Int = (v shl n) or (v ushr (32 - n))

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

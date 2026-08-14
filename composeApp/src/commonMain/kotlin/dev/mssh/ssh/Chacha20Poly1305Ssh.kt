package dev.mssh.ssh

import dev.mssh.crypto.ChaCha20
import dev.mssh.crypto.Poly1305

/**
 * chacha20-poly1305@openssh.com — AEAD packet cipher (PROTOCOL.chacha20poly1305).
 *
 * Two ChaCha20 instances per direction:
 *  - K_1 (main key): encrypts payload+padding; the Poly1305 key is the first 32
 *    bytes of its keystream block at nonce = (0 || seq).
 *  - K_2 (length key): encrypts the 4-byte packet length with a fixed zero nonce.
 * The Poly1305 tag covers the encrypted length || ciphertext.
 */
class Chacha20Poly1305Ssh(private val mainKey: ByteArray, private val lengthKey: ByteArray) {

    private var seq: Long = 0

    /** Returns and increments the per-direction sequence number. */
    fun nextSeq(): Long = seq++


    /** Returns the encrypted 4-byte length for packet [length]. */
    fun encryptLength(length: Long): ByteArray {
        val ks = ChaCha20.block(lengthKey, 0, ZERO_NONCE)
        val out = ByteArray(4)
        for (i in 0 until 4) {
            out[i] = (((length ushr (24 - 8 * i)) and 0xff).toByte().toInt() xor ks[i].toInt()).toByte()
        }
        return out
    }

    /** Decrypts a 4-byte length field. */
    fun decryptLength(encrypted: ByteArray): Long {
        val ks = ChaCha20.block(lengthKey, 0, ZERO_NONCE)
        var v = 0L
        for (i in 0 until 4) {
            val b = (encrypted[i].toInt() xor ks[i].toInt()) and 0xff
            v = (v shl 8) or b.toLong()
        }
        return v
    }

    /**
     * Encrypts [data] into [out] and returns the 16-byte tag covering
     * [encryptedLength] || ciphertext.
     */
    fun encryptAndTag(seq: Long, encryptedLength: ByteArray, data: ByteArray, out: ByteArray, outOffset: Int): ByteArray {
        val nonce = nonceFor(seq)
        val polyKey = ChaCha20.block(mainKey, 0, nonce).copyOf(32)
        var counter = 1L
        var off = 0
        while (off < data.size) {
            val ks = ChaCha20.block(mainKey, counter, nonce)
            val n = minOf(64, data.size - off)
            for (i in 0 until n) out[outOffset + off + i] = (data[off + i].toInt() xor ks[i].toInt()).toByte()
            off += n
            counter++
        }
        val tagInput = ByteArray(encryptedLength.size + data.size)
        encryptedLength.copyInto(tagInput, 0)
        out.copyInto(tagInput, encryptedLength.size, outOffset, outOffset + data.size)
        return Poly1305.mac(polyKey, tagInput)
    }

    /** Decrypts [ciphertext], verifying [tag] over [encryptedLength] || ciphertext. */
    fun decryptAndVerify(seq: Long, encryptedLength: ByteArray, ciphertext: ByteArray, tag: ByteArray): ByteArray {
        val nonce = nonceFor(seq)
        val polyKey = ChaCha20.block(mainKey, 0, nonce).copyOf(32)
        val tagInput = ByteArray(encryptedLength.size + ciphertext.size)
        encryptedLength.copyInto(tagInput, 0)
        ciphertext.copyInto(tagInput, encryptedLength.size)
        val expected = Poly1305.mac(polyKey, tagInput)
        if (!constantTimeEquals(expected, tag)) {
            throw SshException("SSH 包 MAC 校验失败（可能密钥不匹配或网络错误）")
        }
        val plain = ByteArray(ciphertext.size)
        var counter = 1L
        var off = 0
        while (off < ciphertext.size) {
            val ks = ChaCha20.block(mainKey, counter, nonce)
            val n = minOf(64, ciphertext.size - off)
            for (i in 0 until n) plain[off + i] = (ciphertext[off + i].toInt() xor ks[i].toInt()).toByte()
            off += n
            counter++
        }
        return plain
    }

    private fun nonceFor(seq: Long): ByteArray {
        val nonce = ByteArray(12)
        for (i in 0 until 8) nonce[4 + i] = (seq ushr (56 - 8 * i)).toByte()
        return nonce
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    companion object {
        private val ZERO_NONCE = ByteArray(12)
    }
}

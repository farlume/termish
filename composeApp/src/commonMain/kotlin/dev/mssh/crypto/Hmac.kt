package dev.mssh.crypto

/** HMAC (RFC 2104) over SHA-256 / SHA-512. */
object Hmac {

    fun sha256(key: ByteArray, data: ByteArray): ByteArray {
        val block = 64
        val k = normKey(key, block)
        val inner = ByteArray(block + data.size)
        val outer = ByteArray(block + 32)
        for (i in 0 until block) {
            inner[i] = (k[i].toInt() xor 0x36).toByte()
            outer[i] = (k[i].toInt() xor 0x5c).toByte()
        }
        data.copyInto(inner, block)
        val h1 = Sha256.digest(inner)
        h1.copyInto(outer, block)
        return Sha256.digest(outer)
    }

    fun sha512(key: ByteArray, data: ByteArray): ByteArray {
        val block = 128
        val k = normKey(key, block)
        val inner = ByteArray(block + data.size)
        val outer = ByteArray(block + 64)
        for (i in 0 until block) {
            inner[i] = (k[i].toInt() xor 0x36).toByte()
            outer[i] = (k[i].toInt() xor 0x5c).toByte()
        }
        data.copyInto(inner, block)
        val h1 = Sha512.digest(inner)
        h1.copyInto(outer, block)
        return Sha512.digest(outer)
    }

    private fun normKey(key: ByteArray, block: Int): ByteArray {
        if (key.size <= block) return key.copyOf(block)
        return if (block == 128) Sha512.digest(key) else Sha256.digest(key)
    }
}

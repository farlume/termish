package dev.mssh.crypto

/**
 * Arithmetic in GF(2^255 - 19) with 8×32-bit little-endian limbs.
 * Pure Kotlin, no BigInteger — works on JVM, Android and Kotlin/Native.
 *
 * All public functions return fully reduced values in [0, p) unless documented otherwise.
 */
object Field {

    private const val MASK = 0xffffffffL

    // p = 2^255 - 19 as 8×32-bit limbs
    private val P = longArrayOf(
        0xffffffffL - 18, 0xffffffffL, 0xffffffffL, 0xffffffffL,
        0xffffffffL, 0xffffffffL, 0xffffffffL, 0x7fffffffL,
    )

    val ONE = feOf(1)
    val ZERO = feOf(0)

    fun feOf(v: Long): LongArray {
        val r = LongArray(8)
        r[0] = v and MASK
        r[1] = (v ushr 32) and MASK
        return r
    }

    /** Decode 32 little-endian bytes into a field element (masks the top bit). */
    fun fromBytes(b: ByteArray): LongArray {
        val r = LongArray(8)
        for (i in 0 until 8) {
            var v = 0L
            for (j in 0 until 4) v = v or ((b[i * 4 + j].toLong() and 0xff) shl (8 * j))
            r[i] = v and MASK
        }
        r[7] = r[7] and 0x7fffffffL
        return r
    }

    /** Encode a field element into 32 little-endian bytes. */
    fun toBytes(a: LongArray): ByteArray {
        val n = modP(a)
        val out = ByteArray(32)
        for (i in 0 until 8) {
            var v = n[i]
            for (j in 0 until 4) {
                out[i * 4 + j] = (v and 0xff).toByte()
                v = v shr 8
            }
        }
        return out
    }

    fun add(a: LongArray, b: LongArray): LongArray {
        // inputs must be reduced (< p); sum < 2p < 2^256
        val r = LongArray(8)
        var carry = 0L
        for (i in 0 until 8) {
            val t = a[i] + b[i] + carry
            r[i] = t and MASK
            carry = t shr 32
        }
        if (carry != 0L) {
            // 2^256 ≡ 38 (mod p); result < p + 38, reduce once
            val t = r[0] + 38
            r[0] = t and MASK
            var c = t shr 32
            for (i in 1 until 8) {
                if (c == 0L) break
                val tt = r[i] + c
                r[i] = tt and MASK
                c = tt shr 32
            }
        }
        return modP(r)
    }

    /** a - b (both reduced), result fully reduced. */
    fun sub(a: LongArray, b: LongArray): LongArray {
        val r = LongArray(8)
        var borrow = 0L
        for (i in 0 until 8) {
            val t = a[i] - b[i] - borrow
            if (t < 0) {
                r[i] = t + (1L shl 32)
                borrow = 1
            } else {
                r[i] = t
                borrow = 0
            }
        }
        if (borrow != 0L) {
            // a - b + p
            var c = 0L
            for (i in 0 until 8) {
                val t = r[i] + P[i] + c
                r[i] = t and MASK
                c = t shr 32
            }
        }
        return r
    }

    fun mul(a: LongArray, b: LongArray): LongArray {
        val t = LongArray(16)
        // 32-bit limb multiply via 16-bit halves so all intermediates fit in Long:
        //   a_i*b_j = ahi*bhi*2^32 + (ahi*blo + alo*bhi)*2^16 + alo*blo
        // per output limb: 8 terms of alo*blo (< 2^32), 8 of (alo*bhi+ahi*blo)<<16 (< 2^49),
        // 8 of ahi*bhi (< 2^32) -> worst-case accumulation < 2^52.
        for (i in 0 until 8) {
            val ai = a[i]
            val ahi = ai ushr 16
            val alo = ai and 0xffffL
            for (j in 0 until 8) {
                val bj = b[j]
                val bhi = bj ushr 16
                val blo = bj and 0xffffL
                t[i + j] += alo * blo
                t[i + j] += (alo * bhi + ahi * blo) shl 16
                t[i + j + 1] += ahi * bhi
            }
        }
        // propagate carries so limbs fit in 32 bits before folding
        for (pass in 0 until 2) {
            for (i in 0 until 15) {
                val c = t[i] shr 32
                t[i] = t[i] and MASK
                t[i + 1] += c
            }
            t[15] = t[15] and MASK
        }
        // fold: 2^256 ≡ 38 (mod p)
        for (i in 0 until 8) t[i] += t[i + 8] * 38
        // propagate through low 8 limbs
        for (pass in 0 until 3) {
            for (i in 0 until 7) {
                val c = t[i] shr 32
                t[i] = t[i] and MASK
                t[i + 1] += c
            }
            val c = t[7] shr 32
            t[7] = t[7] and MASK
            if (c != 0L) {
                t[0] += 38 * c
            } else break
        }
        // now value < 2^256; reduce mod p
        return modP(t.copyOf(8))
    }

    fun sq(a: LongArray): LongArray = mul(a, a)

    /** r = -a mod p; requires a reduced (< p). */
    fun neg(a: LongArray): LongArray = sub(P, a)

    /** a^(p-2) — Fermat inverse via fixed square-and-multiply chain. */
    fun invert(a: LongArray): LongArray {
        // e = p - 2 = 2^255 - 21 (little-endian 64-bit limbs)
        val e = longArrayOf(
            0xffffffffffffffebUL.toLong(), 0xffffffffffffffffUL.toLong(),
            0xffffffffffffffffUL.toLong(), 0x7fffffffffffffffL,
        )
        var result = ONE
        var base = a
        for (bit in 0 until 255) {
            if (((e[bit / 64] ushr (bit % 64)) and 1L) != 0L) result = mul(result, base)
            base = sq(base)
        }
        return result
    }

    /** Constant-time conditional swap. */
    fun cswap(swap: Boolean, a: LongArray, b: LongArray) {
        val mask = if (swap) -1L else 0L
        for (i in 0 until 8) {
            val x = mask and (a[i] xor b[i])
            a[i] = a[i] xor x
            b[i] = b[i] xor x
        }
    }

    /** Reduce a value in [0, 2^256) modulo p (up to two conditional subtractions). */
    private fun modP(a: LongArray): LongArray {
        val (r1, borrow1) = subWithBorrow(a, P)
        if (borrow1 != 0L) return a // a < p
        val (r2, borrow2) = subWithBorrow(r1, P)
        if (borrow2 != 0L) return r1 // p <= a < 2p
        return r2 // 2p <= a < 3p
    }

    private fun subWithBorrow(a: LongArray, b: LongArray): Pair<LongArray, Long> {
        val r = LongArray(8)
        var borrow = 0L
        for (i in 0 until 8) {
            val t = a[i] - b[i] - borrow
            if (t < 0) {
                r[i] = t + (1L shl 32)
                borrow = 1
            } else {
                r[i] = t
                borrow = 0
            }
        }
        return r to borrow
    }

    fun isZero(a: LongArray): Boolean {
        val m = modP(a)
        return m.all { it == 0L }
    }

    fun equals(a: LongArray, b: LongArray): Boolean {
        val (d, borrow) = subWithBorrow(a, b)
        if (borrow != 0L) {
            // a < b; check a - b + p == p  => a == b (only if a==b, a-b+p=p, but borrow means a<b so no)
            return false
        }
        return d.all { it == 0L }
    }
}

/**
 * X25519 (RFC 7748) key agreement.
 */
object X25519 {

    private const val A24 = 121665L

    /** Clamp a 32-byte scalar per RFC 7748. */
    fun clamp(scalar: ByteArray): ByteArray {
        val s = scalar.copyOf()
        s[0] = (s[0].toInt() and 248).toByte()
        s[31] = (s[31].toInt() and 127).toByte()
        s[31] = (s[31].toInt() or 64).toByte()
        return s
    }

    fun scalarMult(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        require(privateKey.size == 32 && publicKey.size == 32)
        val scalar = clamp(privateKey)
        val x1 = Field.fromBytes(publicKey)

        var x2 = Field.ONE.copyOf()
        var z2 = Field.ZERO.copyOf()
        var x3 = x1.copyOf()
        var z3 = Field.ONE.copyOf()
        var swap = false

        for (t in 254 downTo 0) {
            val kt = ((scalar[t / 8].toInt() ushr (t % 8)) and 1) == 1
            swap = swap xor kt
            Field.cswap(swap, x2, x3)
            Field.cswap(swap, z2, z3)
            swap = kt

            val a = Field.add(x2, z2)
            val aa = Field.sq(a)
            val b = Field.sub(x2, z2)
            val bb = Field.sq(b)
            val e = Field.sub(aa, bb)
            val c = Field.add(x3, z3)
            val d = Field.sub(x3, z3)
            val da = Field.mul(d, a)
            val cb = Field.mul(c, b)
            x3 = Field.sq(Field.add(da, cb))
            val dacb = Field.sub(da, cb)
            z3 = Field.mul(x1, Field.sq(dacb))
            x2 = Field.mul(aa, bb)
            z2 = Field.mul(e, Field.add(aa, Field.mul(Field.feOf(A24), e)))
        }
        Field.cswap(swap, x2, x3)
        Field.cswap(swap, z2, z3)

        val result = Field.mul(x2, Field.invert(z2))
        val bytes = Field.toBytes(result)
        if (bytes.all { it == 0.toByte() }) return ByteArray(32) // RFC 7748 failure convention
        return bytes
    }

    fun generateKeyPair(random: ByteArray): Pair<ByteArray, ByteArray> {
        require(random.size == 32)
        val scalar = clamp(random)
        val pub = scalarMult(scalar, BASE_POINT)
        return scalar to pub
    }

    // u-coordinate of the base point (9)
    val BASE_POINT: ByteArray = ByteArray(32).also { it[0] = 9 }
}

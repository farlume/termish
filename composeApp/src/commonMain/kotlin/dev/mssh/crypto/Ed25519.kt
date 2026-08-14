package dev.mssh.crypto

/**
 * Ed25519 (RFC 8032) — pure Kotlin.
 * Signing is used for SSH public-key auth, verification for host keys.
 *
 * Field arithmetic in GF(2^255-19) uses 32-bit limbs; scalar arithmetic mod L
 * uses 16-bit limbs so all intermediate products fit in 64 bits (no BigInteger).
 */
object Ed25519 {

    // ---------- constants ----------

    // group order L = 2^252 + 27742317777372353535851937790883648493 (4×64-bit)
    private val L64 = longArrayOf(
        0x5812631a5cf5d3edL, 0x14def9dea2f79cd6L, 0x0000000000000000L, 0x1000000000000000L,
    )

    // L as 16×16-bit little-endian limbs
    private val L16: LongArray = run {
        val out = LongArray(16)
        for (i in 0 until 4) {
            var v = L64[i]
            for (j in 0 until 4) {
                out[i * 4 + j] = v and 0xffffL
                v = v ushr 16
            }
        }
        out
    }

    // d = -121665/121666 mod p (8×32-bit limbs)
    private val D = longArrayOf(
        0x135978a3L, 0x75eb4dcaL, 0x4141d8abL, 0x0700a4dL,
        0x7779e898L, 0x8cc74079L, 0x2b6ffe73L, 0x52036ceeL,
    )

    // sqrt(-1) = 2^((p-1)/4)
    private val SQRT_M1 = longArrayOf(
        0x4a0ea0b0L, 0xc4ee1b27L, 0xad2fe478L, 0x2f431806L,
        0x3dfbd7a7L, 0x2b4d0099L, 0x4fc1df0bL, 0x2b832480L,
    )

    private val BASE_Y = Field.fromBytes(
        hex("5866666666666666666666666666666666666666666666666666666666666666")
    )

    // ---------- point ----------

    private class Point(var x: LongArray, var y: LongArray, var z: LongArray, var t: LongArray)

    private val BASE_POINT: Point = run {
        val y = BASE_Y
        val y2 = Field.sq(y)
        val u = Field.sub(y2, Field.ONE)
        val v = Field.add(Field.mul(D, y2), Field.ONE)
        val x = sqrt(u, v)!!
        val xEven = if ((Field.toBytes(x)[0].toInt() and 1) == 1) Field.neg(x) else x
        Point(xEven, y, Field.ONE, Field.mul(xEven, y))
    }

    private fun pointIdentity(): Point = Point(Field.ZERO, Field.ONE, Field.ONE, Field.ZERO)

    private fun addPoints(p1: Point, p2: Point): Point {
        val a = Field.mul(Field.sub(p1.y, p1.x), Field.sub(p2.y, p2.x))
        val b = Field.mul(Field.add(p1.y, p1.x), Field.add(p2.y, p2.x))
        val c = Field.mul(Field.mul(p1.t, p2.t), Field.mul(D, Field.feOf(2)))
        val d = Field.mul(Field.mul(p1.z, p2.z), Field.feOf(2))
        val e = Field.sub(b, a)
        val f = Field.sub(d, c)
        val g = Field.add(d, c)
        val h = Field.add(b, a)
        return Point(
            Field.mul(e, f),
            Field.mul(g, h),
            Field.mul(f, g),
            Field.mul(e, h),
        )
    }

    private fun doublePoint(p: Point): Point {
        val a = Field.sq(p.x)
        val b = Field.sq(p.y)
        val c = Field.mul(Field.feOf(2), Field.sq(p.z))
        val d = Field.neg(a)
        val e = Field.sub(Field.sq(Field.add(p.x, p.y)), Field.add(a, b))
        val g = Field.add(d, b)
        val f = Field.sub(g, c)
        val h = Field.sub(d, b)
        return Point(
            Field.mul(e, f),
            Field.mul(g, h),
            Field.mul(f, g),
            Field.mul(e, h),
        )
    }

    private fun scalarMult(p: Point, scalarBytes: ByteArray): Point {
        var q = pointIdentity()
        var r = p
        for (i in 0 until 255) {
            val bit = ((scalarBytes[i / 8].toInt() ushr (i % 8)) and 1) == 1
            if (bit) q = addPoints(q, r)
            r = doublePoint(r)
        }
        return q
    }

    private fun scalarMultBase(scalarBytes: ByteArray): Point = scalarMult(BASE_POINT, scalarBytes)

    /** Encode point as 32-byte compressed form. */
    private fun encodePoint(p: Point): ByteArray {
        val zi = Field.invert(p.z)
        val x = Field.mul(p.x, zi)
        val y = Field.mul(p.y, zi)
        val out = Field.toBytes(y)
        val xOdd = (Field.toBytes(x)[0].toInt() and 1) == 1
        if (xOdd) out[31] = (out[31].toInt() or 0x80).toByte()
        return out
    }

    /** Decode 32-byte compressed point; returns null if invalid. */
    private fun decodePoint(bytes: ByteArray): Point? {
        if (bytes.size != 32) return null
        val sign = (bytes[31].toInt() and 0x80) != 0
        val yBytes = bytes.copyOf()
        yBytes[31] = (yBytes[31].toInt() and 0x7f).toByte()
        val y = Field.fromBytes(yBytes)
        // x^2 = (y^2 - 1) / (d*y^2 + 1)
        val y2 = Field.sq(y)
        val u = Field.sub(y2, Field.ONE)
        val v = Field.add(Field.mul(D, y2), Field.ONE)
        val x = sqrt(u, v) ?: return null
        val xOdd = (Field.toBytes(x)[0].toInt() and 1) == 1
        val xi = if (sign xor xOdd) Field.neg(x) else x
        if (sign && Field.isZero(xi)) return null
        return Point(xi, y, Field.ONE, Field.mul(xi, y))
    }

    /** x = u/v^((p+3)/8)-ish; candidate then adjusted by sqrt(-1) if needed; null if not a square. */
    private fun sqrt(u: LongArray, v: LongArray): LongArray? {
        val v3 = Field.mul(Field.sq(v), v)
        val v7 = Field.mul(Field.sq(v3), v)
        // candidate = u * v^3 * (u * v^7)^((p-5)/8), where (p-5)/8 = 2^252 - 3
        val uv7 = Field.mul(u, v7)
        val pow = pow(uv7, 0xfffffffffffffffdUL.toLong(), 0xffffffffffffffffUL.toLong(), 0xffffffffffffffffUL.toLong(), 0x0fffffffffffffffL)
        val candidate = Field.mul(Field.mul(u, v3), pow)
        // check candidate^2 * v == u
        if (Field.equals(Field.mul(Field.sq(candidate), v), u)) return candidate
        val c2 = Field.mul(candidate, SQRT_M1)
        if (Field.equals(Field.mul(Field.sq(c2), v), u)) return c2
        return null
    }

    /** Square-and-multiply with exponent given as 4×64-bit little-endian limbs. */
    private fun pow(base: LongArray, e0: Long, e1: Long, e2: Long, e3: Long): LongArray {
        val e = longArrayOf(e0, e1, e2, e3)
        var result = Field.ONE
        var b = base
        for (bit in 0 until 256) {
            if (((e[bit / 64] ushr (bit % 64)) and 1L) != 0L) result = Field.mul(result, b)
            b = Field.sq(b)
        }
        return result
    }

    private fun pointsEqual(p1: Point, p2: Point): Boolean {
        val x1z2 = Field.mul(p1.x, p2.z)
        val x2z1 = Field.mul(p2.x, p1.z)
        val y1z2 = Field.mul(p1.y, p2.z)
        val y2z1 = Field.mul(p2.y, p1.z)
        return Field.equals(x1z2, x2z1) && Field.equals(y1z2, y2z1)
    }

    // ---------- public API ----------

    fun keyPairFromSeed(seed: ByteArray): Pair<ByteArray, ByteArray> {
        require(seed.size == 32)
        val h = Sha512.digest(seed)
        val a = pruneScalar(h.copyOf(32))
        val aBytes = scalarToBytes(a)
        val A = scalarMultBase(aBytes)
        // RFC 8032: the private key is the 32-byte seed; sign() derives a from it
        return seed to encodePoint(A)
    }

    fun sign(message: ByteArray, privateKey: ByteArray): ByteArray {
        require(privateKey.size == 32)
        val h = Sha512.digest(privateKey)
        val a = pruneScalar(h.copyOf(32))
        val aBytes = scalarToBytes(a)
        val A = encodePoint(scalarMultBase(aBytes))
        val prefix = h.copyOfRange(32, 64)
        val r = scalarFromBytes(Sha512.digest(prefix + message))
        val R = encodePoint(scalarMultBase(scalarToBytes(r)))
        val hram = scalarFromBytes(Sha512.digest(R + A + message))
        val S = addScalars(r, mulScalars(hram, a))
        return R + scalarToBytes(S)
    }

    fun verify(message: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean {
        if (signature.size != 64 || publicKey.size != 32) return false
        val R = decodePoint(signature.copyOf(32)) ?: return false
        val A = decodePoint(publicKey) ?: return false
        val hram = scalarFromBytes(Sha512.digest(signature.copyOf(32) + publicKey + message))
        val s = scalarFromBytes(signature.copyOfRange(32, 64))
        if (gte(s, L16)) return false
        val left = scalarMultBase(signature.copyOfRange(32, 64))
        val right = addPoints(R, scalarMult(A, scalarToBytes(hram)))
        return pointsEqual(left, right)
    }

    // ---------- scalar arithmetic mod L (16-bit limbs) ----------

    private fun pruneScalar(s: ByteArray): LongArray {
        val b = s.copyOf()
        b[0] = (b[0].toInt() and 248).toByte()
        b[31] = (b[31].toInt() and 63).toByte()
        b[31] = (b[31].toInt() or 64).toByte()
        return scalarFromBytes(b)
    }

    private fun scalarFromBytes(b: ByteArray): LongArray {
        val limbs = LongArray((b.size + 1) / 2)
        for (i in b.indices) limbs[i / 2] = limbs[i / 2] or ((b[i].toLong() and 0xff) shl (8 * (i % 2)))
        return modL(limbs)
    }

    private fun scalarToBytes(a: LongArray): ByteArray {
        val m = modL(a)
        val out = ByteArray(32)
        for (i in 0 until 16) {
            out[i * 2] = (m[i] and 0xff).toByte()
            out[i * 2 + 1] = ((m[i] ushr 8) and 0xff).toByte()
        }
        return out
    }

    private fun addScalars(a: LongArray, b: LongArray): LongArray {
        val r = LongArray(16)
        var carry = 0L
        for (i in 0 until 16) {
            val t = a[i] + b[i] + carry
            r[i] = t and 0xffffL
            carry = t ushr 16
        }
        // carry out is at most 1 (a,b < L, sum < 2L < 2^254); fold into r via repeated mod
        return modL(r)
    }

    /** 16×16-bit schoolbook multiply; intermediates < 2^36 — no overflow. */
    private fun mulScalars(a: LongArray, b: LongArray): LongArray {
        val t = LongArray(32)
        for (i in 0 until 16) {
            if (a[i] == 0L) continue
            for (j in 0 until 16) t[i + j] += a[i] * b[j]
        }
        // propagate carries down to 16-bit limbs (one pass suffices: t[30] has a single
        // product < 2^32, so its carry into t[31] is < 2^16, and the product fits in 512 bits)
        for (i in 0 until 31) {
            val c = t[i] ushr 16
            t[i] = t[i] and 0xffffL
            t[i + 1] += c
        }
        return modL(t)
    }

    /** Reduce an up-to-32-limb (16-bit) value modulo L via binary long division. */
    private fun modL(v: LongArray): LongArray {
        val r = LongArray(16)
        val totalBits = v.size * 16
        for (bit in totalBits - 1 downTo 0) {
            // r = r * 2 + bit
            var carry = (v[bit / 16] ushr (bit % 16)) and 1L
            for (i in 0 until 16) {
                val t = (r[i] shl 1) or carry
                r[i] = t and 0xffffL
                carry = t ushr 16
            }
            if (gte(r, L16)) {
                var borrow = 0L
                for (i in 0 until 16) {
                    val t = r[i] - L16[i] - borrow
                    if (t < 0) {
                        r[i] = t + 0x10000L
                        borrow = 1
                    } else {
                        r[i] = t
                        borrow = 0
                    }
                }
            }
        }
        return r
    }

    /** Unsigned comparison a >= b (16-bit limbs). */
    private fun gte(a: LongArray, b: LongArray): Boolean {
        val n = maxOf(a.size, b.size)
        for (i in n - 1 downTo 0) {
            val ai = if (i < a.size) a[i] else 0L
            val bi = if (i < b.size) b[i] else 0L
            if (ai != bi) return ai > bi
        }
        return true
    }

    private fun hex(s: String): ByteArray {
        val out = ByteArray(s.length / 2)
        for (i in out.indices) out[i] = s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        return out
    }
}

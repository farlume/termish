package dev.mssh.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CryptoTest {

    private fun hex(s: String): ByteArray {
        val out = ByteArray(s.length / 2)
        for (i in out.indices) out[i] = s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        return out
    }

    private fun ByteArray.toHex(): String = buildString(size * 2) {
        for (b in this@toHex) {
            val v = b.toInt() and 0xff
            append(HEX_CHARS[v ushr 4])
            append(HEX_CHARS[v and 0xf])
        }
    }

    private val HEX_CHARS = "0123456789abcdef"

    @Test
    fun sha256_abc() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Sha256.digest("abc".encodeToByteArray()).toHex()
        )
    }

    @Test
    fun sha256_empty_and_long() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Sha256.digest(ByteArray(0)).toHex()
        )
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            Sha256.digest("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()).toHex()
        )
    }

    @Test
    fun sha512_abc() {
        assertEquals(
            "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a" +
                    "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f",
            Sha512.digest("abc".encodeToByteArray()).toHex()
        )
    }

    @Test
    fun hmacSha256_rfc4231() {
        val key = ByteArray(20) { 0x0b }
        val data = "Hi There".encodeToByteArray()
        assertEquals(
            "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",
            Hmac.sha256(key, data).toHex()
        )
    }

    @Test
    fun chacha20_rfc8439_block() {
        // RFC 8439 §2.3.2 — 64-byte keystream block at counter 1
        val key = ByteArray(32) { it.toByte() }
        val nonce = hex("000000090000004a00000000")
        val stream = ChaCha20.block(key, 1, nonce)
        assertEquals(
            "10f1e7e4d13b5915500fdd1fa32071c4c7d1f4c733c068030422aa9ac3d46c4e" +
                    "d2826446079faa0914c2d705d98b02a2b5129cd1de164eb9cbd083e8a2503c4e",
            stream.toHex()
        )
    }

    @Test
    fun chacha20_encrypt_rfc8439() {
        // RFC 8439 §2.4.2 — encryption of a plaintext, initial counter 1
        val key = hex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f")
        val nonce = hex("000000000000004a00000000")
        val plain = "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it."
        val expected = hex(
            "5bef610976390c5c227c04dbce252cfb00adcc9fdd984bd624822cb04950dc6757" +
                    "517a20f6a1cb9c0b5871f542fc97a394575cbf41bdfbb39c581dfa0bf3cf1e02" +
                    "88ecfe59cc08d49beeaa539f3065be998dc189161b441f62156cc2789c847c68" +
                    "cde0c57b7784e7d609d1d4555549535452"
        )
        val out = ByteArray(plain.length)
        var counter = 1L
        var off = 0
        while (off < plain.length) {
            val ks = ChaCha20.block(key, counter, nonce)
            val n = minOf(64, plain.length - off)
            for (i in 0 until n) {
                out[off + i] = (plain[off + i].code xor ks[i].toInt()).toByte()
            }
            off += n
            counter++
        }
        assertEquals(expected.toHex(), out.toHex())
    }

    @Test
    fun poly1305_rfc8439() {
        val key = hex("85d6be7857556d337f4452fe42d506a80103808afb0db2fd4abff6af4149f51b")
        val msg = "Cryptographic Forum Research Group".encodeToByteArray()
        val tag = Poly1305.mac(key, msg)
        assertEquals("a8061dc1305136c6c22b8baf0c0127a9", tag.toHex())
    }

    @Test
    fun poly1305_empty() {
        val key = ByteArray(32) { it.toByte() }
        // empty message: h = 0; tag = s
        val tag = Poly1305.mac(key, ByteArray(0))
        assertEquals(key.copyOfRange(16, 32).toHex(), tag.toHex())
    }

    @Test
    fun x25519_rfc7748_52() {
        val scalar = hex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4")
        val u = hex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c")
        val out = X25519.scalarMult(scalar, u)
        assertEquals(
            "c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552",
            out.toHex()
        )
    }

    @Test
    fun x25519_rfc7748_61() {
        val alice = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val bob = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        val alicePub = X25519.scalarMult(alice, X25519.BASE_POINT)
        val bobPub = X25519.scalarMult(bob, X25519.BASE_POINT)
        assertEquals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a", alicePub.toHex())
        assertEquals("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f", bobPub.toHex())
        assertEquals(
            "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742",
            X25519.scalarMult(alice, bobPub).toHex()
        )
        assertEquals(
            "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742",
            X25519.scalarMult(bob, alicePub).toHex()
        )
    }

    @Test
    fun ed25519_rfc8032_test1() {
        val seed = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        val (priv, pub) = Ed25519.keyPairFromSeed(seed)
        assertEquals("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a", pub.toHex())
        val sig = Ed25519.sign(ByteArray(0), priv)
        assertEquals(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555" +
                    "fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
            sig.toHex()
        )
        assertTrue(Ed25519.verify(ByteArray(0), sig, pub))
    }

    @Test
    fun ed25519_rfc8032_test2() {
        val seed = hex("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb")
        val (priv, pub) = Ed25519.keyPairFromSeed(seed)
        assertEquals("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c", pub.toHex())
        val msg = hex("72")
        val sig = Ed25519.sign(msg, priv)
        assertEquals(
            "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da" +
                    "085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00",
            sig.toHex()
        )
        assertTrue(Ed25519.verify(msg, sig, pub))
        assertTrue(!Ed25519.verify(ByteArray(0), sig, pub))
    }

    @Test
    fun ed25519_rfc8032_test3() {
        val seed = hex("c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7")
        val msg = hex("af82")
        val (priv, pub) = Ed25519.keyPairFromSeed(seed)
        assertEquals("fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025", pub.toHex())
        val sig = Ed25519.sign(msg, priv)
        assertEquals(
            "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac" +
                    "18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a",
            sig.toHex()
        )
        assertTrue(Ed25519.verify(msg, sig, pub))
    }

    @Test
    fun ed25519_roundtrip() {
        val seed = ByteArray(32) { (it * 7).toByte() }
        val (priv, pub) = Ed25519.keyPairFromSeed(seed)
        repeat(3) { i ->
            val msg = ByteArray(100 + i * 50) { (it + i).toByte() }
            val sig = Ed25519.sign(msg, priv)
            assertTrue(Ed25519.verify(msg, sig, pub))
            val bad = msg.copyOf()
            bad[0] = (bad[0].toInt() xor 0xff).toByte()
            assertTrue(!Ed25519.verify(bad, sig, pub))
        }
    }

    @Test
    fun x25519_agreement_roundtrip() {
        val a = ByteArray(32) { 0x11 }
        val b = ByteArray(32) { 0x22 }
        val aPub = X25519.scalarMult(a, X25519.BASE_POINT)
        val bPub = X25519.scalarMult(b, X25519.BASE_POINT)
        val shared1 = X25519.scalarMult(a, bPub)
        val shared2 = X25519.scalarMult(b, aPub)
        assertTrue(shared1.contentEquals(shared2))
        assertTrue(shared1.any { it != 0.toByte() })
    }
}

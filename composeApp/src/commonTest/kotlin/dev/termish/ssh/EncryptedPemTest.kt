package dev.termish.ssh

import dev.termish.util.base64Encode
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [isEncryptedPem] 的格式识别：PKCS#8 头 / 传统 PEM 头 / openssh-key-v1 ciphername。 */
class EncryptedPemTest {
    private fun opensshBlob(cipher: String): ByteArray {
        val marker = "openssh-key-v1\u0000".encodeToByteArray()
        val cipherBytes = cipher.encodeToByteArray()
        val len = ByteArray(4)
        len[0] = ((cipherBytes.size shr 24) and 0xFF).toByte()
        len[1] = ((cipherBytes.size shr 16) and 0xFF).toByte()
        len[2] = ((cipherBytes.size shr 8) and 0xFF).toByte()
        len[3] = (cipherBytes.size and 0xFF).toByte()
        return marker + len + cipherBytes
    }

    private fun opensshPem(cipher: String): String =
        "-----BEGIN OPENSSH PRIVATE KEY-----\n" +
            base64Encode(opensshBlob(cipher)) +
            "\n-----END OPENSSH PRIVATE KEY-----\n"

    @Test
    fun openSshNoneCipherIsPlaintext() {
        assertFalse(isEncryptedPem(opensshPem("none")))
    }

    @Test
    fun openSshEncryptedCipherIsEncrypted() {
        assertTrue(isEncryptedPem(opensshPem("aes256-ctr")))
        assertTrue(isEncryptedPem(opensshPem("aes128-cbc")))
    }

    @Test
    fun pkcs8Headers() {
        assertTrue(isEncryptedPem("-----BEGIN ENCRYPTED PRIVATE KEY-----\nAAAA\n-----END ENCRYPTED PRIVATE KEY-----\n"))
        assertFalse(isEncryptedPem("-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----\n"))
    }

    @Test
    fun legacyPemHeader() {
        assertTrue(
            isEncryptedPem(
                """
                -----BEGIN RSA PRIVATE KEY-----
                Proc-Type: 4,ENCRYPTED
                DEK-Info: AES-128-CBC,ABCDEF
                AAAA
                -----END RSA PRIVATE KEY-----
                """.trimIndent(),
            ),
        )
        assertFalse(
            isEncryptedPem(
                """
                -----BEGIN RSA PRIVATE KEY-----
                AAAA
                -----END RSA PRIVATE KEY-----
                """.trimIndent(),
            ),
        )
    }
}

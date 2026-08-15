package dev.mssh.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.mssh.AppContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android 安全存储：Android Keystore 主密钥 + AES/GCM 加密，密文存 SharedPreferences。
 */
actual object SecretStore {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "mssh_master_key"
    private const val PREFS = "mssh_secrets"
    private const val GCM_TAG_BITS = 128

    private fun prefs(): SharedPreferences =
        AppContext.get().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(plain.encodeToByteArray())
        val iv = cipher.iv
        val out = ByteArray(iv.size + ciphertext.size)
        iv.copyInto(out, 0)
        ciphertext.copyInto(out, iv.size)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(data: String): String {
        val raw = Base64.decode(data, Base64.NO_WRAP)
        val iv = raw.copyOfRange(0, 12)
        val ciphertext = raw.copyOfRange(12, raw.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext).decodeToString()
    }

    actual fun get(service: String, account: String): String? {
        val enc = prefs().getString("$service/$account", null) ?: return null
        return try {
            decrypt(enc)
        } catch (e: Exception) {
            null
        }
    }

    actual fun set(service: String, account: String, value: String) {
        prefs().edit().putString("$service/$account", encrypt(value)).apply()
    }

    actual fun delete(service: String, account: String) {
        prefs().edit().remove("$service/$account").apply()
    }
}

package dev.termish.data

/**
 * 平台安全存储：密码 / 私钥等秘密。keychain（iOS）/ Keystore（Android）。
 */
expect object SecretStore {
    fun get(service: String, account: String): String?
    fun set(service: String, account: String, value: String)
    fun delete(service: String, account: String)
}

/** 秘密存储用的 service 名。 */
const val SECRET_SERVICE = "dev.termish.secrets"

fun secretAccountFor(hostId: String, kind: String): String = "$hostId.$kind"

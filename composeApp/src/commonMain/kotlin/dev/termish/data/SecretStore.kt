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

/** 火山引擎流式语音识别 API Key（新版控制台 API Key 管理页获取）的存储账号。 */
const val ASR_API_KEY_ACCOUNT = "asr.apiKey"

/** 语音识别服务实例的密钥账号（provider 粒度，见 AsrProvider）。 */
fun asrKeyAccount(providerId: String): String = "asr.$providerId.apiKey"

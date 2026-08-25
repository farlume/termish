package dev.termish.data

/**
 * 平台安全存储：密码 / 私钥等秘密。keychain（iOS）/ Keystore（Android）。
 */
expect object SecretStore {
    fun get(
        service: String,
        account: String,
    ): String?

    fun set(
        service: String,
        account: String,
        value: String,
    )

    fun delete(
        service: String,
        account: String,
    )
}

/** 秘密存储用的 service 名。 */
const val SECRET_SERVICE = "dev.termish.secrets"

fun secretAccountFor(
    hostId: String,
    kind: String,
): String = "$hostId.$kind"

/** 从平台安全存储解析主机认证凭据，返回 password to privateKey。 */
fun resolveCredentials(host: Host): Pair<String?, String?> {
    val password = SecretStore.get(SECRET_SERVICE, secretAccountFor(host.id, "password"))
    val privateKey = SecretStore.get(SECRET_SERVICE, secretAccountFor(host.id, "privateKey"))
    return when (host.authMethod) {
        HostAuthMethod.PASSWORD -> password to null
        HostAuthMethod.PRIVATE_KEY -> null to privateKey
        HostAuthMethod.KEY_OR_PASSWORD -> password to privateKey
    }
}

/** 火山引擎流式语音识别 API Key（新版控制台 API Key 管理页获取）的存储账号。 */
const val ASR_API_KEY_ACCOUNT = "asr.apiKey"

/** 语音识别服务实例的密钥账号（provider 粒度，见 AsrProvider）。 */
fun asrKeyAccount(providerId: String): String = "asr.$providerId.apiKey"

/** Agent 模型供应商 API Key（provider 粒度，不写入普通 Settings）。 */
fun agentProviderKeyAccount(providerId: String): String = "agent.provider.$providerId.apiKey"

package dev.mssh.ssh

/**
 * 认证方式。
 */
enum class SshAuthMethod { PASSWORD, KEYBOARD_INTERACTIVE, PUBLIC_KEY, NONE }

/** 认证交互提示（keyboard-interactive / password 变更）。 */
data class AuthPrompt(
    val method: SshAuthMethod,
    val name: String,
    val instruction: String,
    val prompts: List<PromptField>,
)

data class PromptField(val label: String, val echo: Boolean)

/** 服务器主机密钥指纹（用于 TOFU / known-hosts 校验）。 */
data class HostKeyInfo(
    val algorithm: String,
    val fingerprintSha256: String,
    val fingerprintMd5: String,
)

data class SessionInfo(
    val serverVersion: String,
    val hostKey: HostKeyInfo?,
    val kexAlgorithm: String,
)

/** 一次连接所需的全部参数（不含回调）。 */
data class SshConnection(
    val host: String,
    val port: Int = 22,
    val username: String,
    val password: String? = null,
    val privateKeyPem: String? = null,
    val connectTimeoutMillis: Long = 15_000,
)

/**
 * 会话回调。所有回调都在会话的后台线程上触发（[onPrompt] 除外，见下）。
 *
 * - [onOutput]/[onStderr]/[onExitStatus]/[onClosed] 必须线程安全并尽快返回，
 *   由上层决定如何切回 UI 线程。
 * - [onPrompt] 是挂起函数：引擎在认证流程中调用它，允许上层弹出 UI。
 * - [verifyHostKey] 在握手阶段同步调用；返回 false 将中止连接。
 */
interface SshCallbacks {
    fun onOutput(data: ByteArray)
    fun onStderr(data: ByteArray)
    fun onExitStatus(status: Int)
    fun onClosed(reason: String?)

    suspend fun onPrompt(prompt: AuthPrompt): List<String>?

    fun verifyHostKey(hostKey: HostKeyInfo): Boolean
}

/**
 * 平台无关的 SSH 会话抽象。引擎由平台注入（JVM: sshj；iOS: libssh2/NMSSH）。
 */
interface SshSession {
    /** 阻塞：连接 + 认证 + 打开 session 通道 + 启动 shell。 */
    fun connectAndStart(columns: Int, rows: Int): SessionInfo

    /** 通知远端窗口尺寸变化（字节/像素）。 */
    fun resize(columns: Int, rows: Int, widthPx: Int, heightPx: Int)

    /** 向远端 shell 写入字节（键盘输入）。 */
    fun sendData(data: ByteArray)

    /** 主动关闭会话。 */
    fun close()

    fun isActive(): Boolean
}

/** 平台工厂：创建对应引擎的 [SshSession]。 */
expect fun createSshSession(connection: SshConnection, callbacks: SshCallbacks): SshSession

class SshException(message: String, cause: Throwable? = null) : Exception(message, cause)

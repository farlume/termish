package dev.termish.ssh

import dev.termish.util.base64Decode

/**
 * 认证方式。
 */
enum class SshAuthMethod { PASSWORD, KEYBOARD_INTERACTIVE, PUBLIC_KEY, PASSPHRASE, NONE }

/** 认证交互提示（keyboard-interactive / password 变更）。 */
data class AuthPrompt(
    val method: SshAuthMethod,
    val name: String,
    val instruction: String,
    val prompts: List<PromptField>,
)

data class PromptField(val label: String, val echo: Boolean)

/**
 * 判断 PEM 私钥是否加密（passphrase-protected）：
 * - PKCS#8：`BEGIN ENCRYPTED PRIVATE KEY`
 * - 传统 PEM：`Proc-Type: 4,ENCRYPTED`
 * - OpenSSH：解码 `openssh-key-v1` 头部的 ciphername，非 `none` 即加密
 */
internal fun isEncryptedPem(pem: String): Boolean {
    if (pem.contains("ENCRYPTED PRIVATE KEY") || pem.contains("Proc-Type: 4,ENCRYPTED")) return true
    if (!pem.contains("OPENSSH PRIVATE KEY")) return false
    return try {
        val b64 = pem.lineSequence().filter { !it.startsWith("-----") }.joinToString("")
        val bytes = base64Decode(b64)
        // openssh-key-v1 的 magic 是 15 字节（含结尾 NUL），少一个字节会让
        // ciphername 长度读取错位、把明文 key 误判为加密
        val marker = "openssh-key-v1\u0000".encodeToByteArray()
        if (bytes.size < marker.size + 8 || !bytes.copyOfRange(0, marker.size).contentEquals(marker)) {
            return false
        }
        var off = marker.size
        val clen = readIntBE(bytes, off)
        off += 4
        // ciphername 为 ASCII；跨平台不能用 JVM 的 Charsets
        val cipher = buildString {
            for (b in bytes.copyOfRange(off, off + clen)) append(b.toInt().toChar())
        }
        cipher != "none"
    } catch (_: Exception) {
        // 解析失败按明文处理，认证阶段自然失败后再提示口令
        false
    }
}

private fun readIntBE(b: ByteArray, at: Int): Int =
    ((b[at].toInt() and 0xFF) shl 24) or
        ((b[at + 1].toInt() and 0xFF) shl 16) or
        ((b[at + 2].toInt() and 0xFF) shl 8) or
        (b[at + 3].toInt() and 0xFF)

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

/** 单条命令执行结果（Mosh 引导等）。 */
data class CommandResult(
    val output: String,
    val hostKey: HostKeyInfo?,
)

/** 一次连接所需的全部参数（不含回调）。 */
data class SshConnection(
    val host: String,
    val port: Int = 22,
    val username: String,
    val password: String? = null,
    val privateKeyPem: String? = null,
    val connectTimeoutMillis: Long = 15_000,
    /** 空闲保活间隔（秒）；<=0 表示不发送应用层 keepalive。 */
    val keepAliveSeconds: Int = 30,
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
 * 平台无关的 SSH 会话抽象。引擎由平台注入（JVM: sshj；iOS: libssh2/NTermish）。
 */
interface SshSession {
    /** 阻塞：连接 + 认证 + 打开 session 通道 + 启动 shell。 */
    fun connectAndStart(columns: Int, rows: Int): SessionInfo

    /** 通知远端窗口尺寸变化（字节/像素）。 */
    fun resize(columns: Int, rows: Int, widthPx: Int, heightPx: Int)

    /** 向远端 shell 写入字节（键盘输入）。 */
    fun sendData(data: ByteArray)

    /** 执行单条命令并等待退出（Mosh 引导等一次性调用；非交互 exec 通道）。 */
    fun connectAndRun(command: String, timeoutMs: Long = 15_000): CommandResult

    /**
     * 探测远端系统（Termius 式自动识别）。在已认证的同一连接上开临时 exec
     * 通道执行 [SYSTEM_PROBE_COMMAND]，返回原始输出；失败返回 null。
     * 不重新认证、不影响交互通道。
     */
    fun probeSystem(): String?

    /** 主动关闭会话。 */
    fun close()

    fun isActive(): Boolean
}

/** 平台工厂：创建对应引擎的 [SshSession]。 */
expect fun createSshSession(connection: SshConnection, callbacks: SshCallbacks): SshSession

class SshException(message: String, cause: Throwable? = null) : Exception(message, cause)

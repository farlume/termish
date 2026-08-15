package dev.mssh.ssh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.connection.channel.AbstractChannel
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.connection.channel.direct.SessionChannel
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.keyprovider.FileKeyProvider
import net.schmizz.sshj.userauth.keyprovider.PKCS8KeyFile
import net.schmizz.sshj.userauth.method.AuthKeyboardInteractive
import net.schmizz.sshj.userauth.method.AuthMethod
import net.schmizz.sshj.userauth.method.AuthPassword
import net.schmizz.sshj.userauth.method.AuthPublickey
import net.schmizz.sshj.userauth.method.ChallengeResponseProvider
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import java.io.StringReader
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64

/**
 * sshj 引擎（Android / desktop 共用）。传输层、KEX、认证、加密全部交给
 * 久经考验的 sshj + BouncyCastle；本类只负责会话生命周期与回调。
 */
class SshSessionSshj(
    private val connection: SshConnection,
    private val callbacks: SshCallbacks,
) : SshSession {

    private val client = SSHClient()
    private var session: Session? = null
    private var shell: Session.Shell? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    // 单线程写调度器：既避免在主线程做 socket 写（Android 会抛 NetworkOnMainThreadException），又保证输入顺序
    private val writeDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    private var hostKeyInfo: HostKeyInfo? = null

    override fun connectAndStart(columns: Int, rows: Int): SessionInfo {
        client.addHostKeyVerifier(object : HostKeyVerifier {
            override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
                val algorithm = keyTypeName(key)
                val info = HostKeyInfo(
                    algorithm = algorithm,
                    fingerprintSha256 = fingerprintSha256(key),
                    fingerprintMd5 = fingerprintMd5(key),
                )
                hostKeyInfo = info
                return callbacks.verifyHostKey(info)
            }

            override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
        })

        client.setConnectTimeout(connection.connectTimeoutMillis.toInt())
        client.setTimeout(0) // 交互会话不设读超时

        client.connect(connection.host, connection.port)
        val serverVersion = client.transport.serverVersion

        authenticate()

        val s = client.startSession()
        session = s
        s.allocatePTY("xterm-256color", columns, rows, 0, 0, emptyMap())
        val sh = s.startShell()
        shell = sh

        // 空闲保活：30s 发送 keepalive
        try {
            client.connection.keepAlive.setKeepAliveInterval(30)
        } catch (_: Exception) {
        }

        startReaders(sh)
        return SessionInfo(
            serverVersion = serverVersion,
            hostKey = hostKeyInfo,
            kexAlgorithm = hostKeyInfo?.algorithm ?: "",
        )
    }

    // ---------- 认证 ----------

    private fun authenticate() {
        val methods = ArrayList<AuthMethod>()

        connection.privateKeyPem?.let { pem ->
            loadKeyProvider(pem)?.let { methods.add(AuthPublickey(it)) }
        }
        connection.password?.let { pw ->
            val finder = object : PasswordFinder {
                override fun reqPassword(resource: Resource<*>?): CharArray = pw.toCharArray()
                override fun shouldRetry(resource: Resource<*>?): Boolean = false
            }
            methods.add(AuthPassword(finder))
        }
        // keyboard-interactive：作为密码/二次验证的兜底
        methods.add(AuthKeyboardInteractive(kbiProvider))

        client.auth(connection.username, methods)
    }

    private fun loadKeyProvider(pem: String): FileKeyProvider? {
        val isOpenSsh = pem.contains("OPENSSH PRIVATE KEY")
        return try {
            val kp: FileKeyProvider = if (isOpenSsh) {
                com.hierynomus.sshj.userauth.keyprovider.OpenSSHKeyV1KeyFile()
            } else {
                PKCS8KeyFile()
            }
            kp.init(StringReader(pem), null, null)
            kp
        } catch (e: Exception) {
            // 加密私钥暂不支持：交给上层提示
            null
        }
    }

    private val kbiProvider = object : ChallengeResponseProvider {
        @Volatile private var cancelled = false
        @Volatile private var name = ""
        @Volatile private var instruction = ""

        override fun getSubmethods(): List<String> = emptyList()

        override fun init(resource: Resource<*>, name: String, instruction: String) {
            this.name = name
            this.instruction = instruction
        }

        override fun getResponse(prompt: String, echo: Boolean): CharArray {
            val answers = runBlocking {
                callbacks.onPrompt(
                    AuthPrompt(
                        method = SshAuthMethod.KEYBOARD_INTERACTIVE,
                        name = name,
                        instruction = instruction,
                        prompts = listOf(PromptField(prompt, echo)),
                    )
                )
            }
            if (answers.isNullOrEmpty()) {
                cancelled = true
                return CharArray(0)
            }
            return answers.first().toCharArray()
        }

        override fun shouldRetry(): Boolean = !cancelled
    }

    // ---------- 数据收发 ----------

    private fun startReaders(sh: Session.Shell) {
        scope.launch {
            try {
                val buf = ByteArray(64 * 1024)
                val input = sh.inputStream
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) callbacks.onOutput(buf.copyOf(n))
                }
            } catch (_: Exception) {
            }
        }
        scope.launch {
            try {
                val err = sh.errorStream ?: return@launch
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = err.read(buf)
                    if (n < 0) break
                    if (n > 0) callbacks.onStderr(buf.copyOf(n))
                }
            } catch (_: Exception) {
            }
        }
        scope.launch {
            // 等待通道关闭，报告退出状态
            try {
                (sh as? AbstractChannel)?.join()
            } catch (_: Exception) {
                // 通道可能以断开结束
                while ((sh as? AbstractChannel)?.isOpen == true) delay(50)
            }
            handleClosed()
        }
    }

    private fun handleClosed() {
        if (!closed.compareAndSet(false, true)) return
        try {
            val sc = shell as? SessionChannel
            if (sc != null) {
                // 通道关闭后退出状态可能稍后到达，短暂轮询
                var status: Int? = null
                val deadline = System.currentTimeMillis() + 2000
                while (status == null && System.currentTimeMillis() < deadline) {
                    status = sc.getExitStatus()
                    if (status == null) Thread.sleep(20)
                }
                status?.let { callbacks.onExitStatus(it) }
            }
        } catch (_: Exception) {
        }
        callbacks.onClosed(null)
    }

    override fun resize(columns: Int, rows: Int, widthPx: Int, heightPx: Int) {
        scope.launch(writeDispatcher) {
            try {
                shell?.changeWindowDimensions(columns, rows, widthPx, heightPx)
            } catch (_: Exception) {
            }
        }
    }

    override fun sendData(data: ByteArray) {
        scope.launch(writeDispatcher) {
            try {
                shell?.outputStream?.write(data)
                shell?.outputStream?.flush()
            } catch (_: Exception) {
            }
        }
    }

    override fun close() {
        scope.launch(writeDispatcher) {
            try {
                shell?.close()
            } catch (_: Exception) {
            }
            try {
                session?.close()
            } catch (_: Exception) {
            }
            try {
                client.disconnect()
            } catch (_: Exception) {
            }
        }
        if (!closed.get()) {
            closed.set(true)
            callbacks.onClosed(null)
        }
    }

    override fun isActive(): Boolean = !closed.get()

    // ---------- 指纹 ----------

    private fun keyTypeName(key: PublicKey): String = try {
        KeyType.fromKey(key).toString()
    } catch (_: Exception) {
        key.algorithm
    }

    private fun fingerprintSha256(key: PublicKey): String {
        val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
        val digest = MessageDigest.getInstance("SHA-256").digest(blob)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }

    private fun fingerprintMd5(key: PublicKey): String {
        val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
        val digest = MessageDigest.getInstance("MD5").digest(blob)
        return digest.joinToString(":") { "%02x".format(it) }
    }
}

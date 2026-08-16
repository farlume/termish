package dev.mssh.ssh

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * sshj 引擎集成测试：连接本地测试 sshd（127.0.0.1:2222）并运行命令。
 *
 * 需要：MSSH_TEST_RUN=1，且 test sshd 已启动（见 scripts/test-sshd.sh）。
 */
class SshjIntegrationTest {

    private fun env(key: String, default: String): String = System.getenv(key) ?: default

    private fun runSession(connection: SshConnection, passphrase: String? = null) {
        val output = StringBuilder()
        val closed = AtomicBoolean(false)
        val closeReason = AtomicReference<String?>(null)
        val infoRef = AtomicReference<SessionInfo?>(null)

        val session = createSshSession(
            connection,
            object : SshCallbacks {
                override fun onOutput(data: ByteArray) {
                    output.append(String(data, Charsets.UTF_8))
                }

                override fun onStderr(data: ByteArray) {
                    output.append(String(data, Charsets.UTF_8))
                }

                override fun onExitStatus(status: Int) {
                    println("exit status: $status")
                }

                override fun onClosed(reason: String?) {
                    closed.set(true)
                    closeReason.set(reason)
                }

                override suspend fun onPrompt(prompt: AuthPrompt): List<String>? {
                    println("PROMPT: ${prompt.name} ${prompt.instruction}")
                    if (prompt.method == SshAuthMethod.PASSPHRASE && passphrase != null) {
                        return listOf(passphrase)
                    }
                    return null
                }

                override fun verifyHostKey(hostKey: HostKeyInfo): Boolean {
                    println("host key: ${hostKey.algorithm} ${hostKey.fingerprintSha256}")
                    return true
                }
            },
        )

        try {
            val info = session.connectAndStart(columns = 100, rows = 40)
            infoRef.set(info)
        } catch (e: Exception) {
            e.printStackTrace()
            throw AssertionError("连接失败: ${e.message}", e)
        }

        assertNotNull(infoRef.get())
        assertContains(infoRef.get()!!.hostKey!!.fingerprintSha256, "SHA256:")

        session.sendData("echo MSSH_SSHJ_OK\nexit 0\n".encodeToByteArray())

        val deadline = System.currentTimeMillis() + 15000
        while (System.currentTimeMillis() < deadline) {
            if (output.toString().contains("MSSH_SSHJ_OK")) break
            Thread.sleep(100)
        }
        Thread.sleep(500)
        session.close()

        println("--- output ---")
        println(output)
        assertContains(output.toString(), "MSSH_SSHJ_OK")
    }

    @Test
    fun publicKeyAuthRunCommand() {
        if (env("MSSH_TEST_RUN", "0") != "1") {
            println("SKIP: set MSSH_TEST_RUN=1")
            return
        }
        val pemFile = File(env("MSSH_TEST_KEY", "/tmp/mssh_test/client"))
        if (!pemFile.exists()) {
            println("SKIP: no key at ${pemFile.absolutePath}")
            return
        }
        runSession(
            SshConnection(
                host = "127.0.0.1",
                port = env("MSSH_TEST_PORT", "2222").toInt(),
                username = System.getProperty("user.name"),
                privateKeyPem = pemFile.readText(),
            )
        )
    }

    @Test
    fun encryptedPrivateKeyAuthWithPassphrase() {
        if (env("MSSH_TEST_RUN", "0") != "1") {
            println("SKIP: set MSSH_TEST_RUN=1")
            return
        }
        val pemFile = File(env("MSSH_TEST_ENC_KEY", "/tmp/mssh_test/enc_client"))
        if (!pemFile.exists()) {
            println("SKIP: no encrypted key at ${pemFile.absolutePath}")
            return
        }
        runSession(
            SshConnection(
                host = "127.0.0.1",
                port = env("MSSH_TEST_PORT", "2222").toInt(),
                username = System.getProperty("user.name"),
                privateKeyPem = pemFile.readText(),
            ),
            passphrase = env("MSSH_TEST_ENC_PASSPHRASE", "mssh-test-pass"),
        )
    }
}

package dev.mssh.ssh

import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** sshj SFTP 集成测试：连本地测试 sshd，验证列目录 / 建目录 / 上传。 */
class SftpIntegrationTest {

    private fun env(key: String, default: String): String = System.getenv(key) ?: default

    private fun sshdReachable(): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", env("MSSH_TEST_PORT", "2222").toInt()), 500) }
    }.isSuccess

    @Test
    fun sftpListMkdirUpload() {
        if (!sshdReachable()) {
            println("SKIP: 测试 sshd 未启动")
            return
        }
        val pemFile = File(env("MSSH_TEST_KEY", "/tmp/mssh_test/client"))
        if (!pemFile.exists()) {
            println("SKIP: no key at ${pemFile.absolutePath}")
            return
        }
        val session = createSftpSession(
            SshConnection(
                host = "127.0.0.1",
                port = env("MSSH_TEST_PORT", "2222").toInt(),
                username = System.getProperty("user.name"),
                privateKeyPem = pemFile.readText(),
            ),
            object : SshCallbacks {
                override fun onOutput(data: ByteArray) {}
                override fun onStderr(data: ByteArray) {}
                override fun onExitStatus(status: Int) {}
                override fun onClosed(reason: String?) {}
                override suspend fun onPrompt(prompt: AuthPrompt): List<String>? = null
                override fun verifyHostKey(hostKey: HostKeyInfo): Boolean = true
            },
        )
        try {
            val base = "/tmp/mssh_test"
            val root = session.list(base)
            assertTrue(root.isNotEmpty(), "应能列出 $base")
            println("root entries: ${root.size}, 示例: ${root.first().name} ${root.first().permissions}")

            val dir = "$base/sftp_test_${System.currentTimeMillis()}"
            session.mkdir(dir)
            session.upload("$dir/hello.txt", "hello sftp".encodeToByteArray())

            val listed = session.list(dir)
            val file = listed.firstOrNull { it.name == "hello.txt" }
            assertTrue(file != null, "上传后应能列出 hello.txt")
            assertTrue(!file!!.isDirectory, "hello.txt 应为文件")
            assertEquals("hello sftp".length.toLong(), file.size, "文件大小")
            println("upload OK: ${file.name} ${file.permissions} size=${file.size}")
        } finally {
            session.close()
        }
    }
}

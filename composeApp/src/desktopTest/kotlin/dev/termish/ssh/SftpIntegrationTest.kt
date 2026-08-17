package dev.termish.ssh

import java.io.File
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** sshj SFTP 集成测试：连本地测试 sshd，验证列目录 / 建目录 / 上传。 */
class SftpIntegrationTest {

    private fun env(key: String, default: String): String = System.getenv(key) ?: default

    private fun sshdReachable(): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", env("Termish_TEST_PORT", "22222").toInt()), 500) }
    }.isSuccess

    private fun newSession(): SftpSession {
        val pemFile = File(env("Termish_TEST_KEY", "/tmp/termish_test/client"))
        check(pemFile.exists()) { "no key at ${pemFile.absolutePath}" }
        return createSftpSession(
            SshConnection(
                host = "127.0.0.1",
                port = env("Termish_TEST_PORT", "22222").toInt(),
                username = System.getProperty("user.name"),
                privateKeyPem = pemFile.readText(),
            ),
            object : SshCallbacks {
                override suspend fun onOutput(data: ByteArray) {}
                override suspend fun onStderr(data: ByteArray) {}
                override fun onExitStatus(status: Int) {}
                override fun onClosed(reason: String?) {}
                override suspend fun onPrompt(prompt: AuthPrompt): List<String>? = null
                override fun verifyHostKey(hostKey: HostKeyInfo): Boolean = true
            },
        )
    }

    @Test
    fun sftpListMkdirUpload() {
        if (!sshdReachable()) {
            println("SKIP: 测试 sshd 未启动")
            return
        }
        val session = newSession()
        try {
            val base = "/tmp/termish_test"
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

    @Test
    fun sftpBinaryRoundTrip() {
        if (!sshdReachable()) {
            println("SKIP: 测试 sshd 未启动")
            return
        }
        val session = newSession()
        try {
            val dir = "/tmp/termish_test/sftp_bin_${System.currentTimeMillis()}"
            session.mkdir(dir)
            // 覆盖 0x00-0xFF 全部字节值（非 UTF-8 文本），验证 upload/download 字节无损
            val data = ByteArray(256 * 257) { (it % 256).toByte() }
            session.upload("$dir/bin.dat", data)

            val out = ByteArrayOutputStream()
            session.download("$dir/bin.dat") { chunk -> out.write(chunk) }
            assertTrue(out.toByteArray().contentEquals(data), "二进制上传/下载往返应一致")
            println("binary round-trip OK: ${data.size} bytes")
        } finally {
            session.close()
        }
    }
}

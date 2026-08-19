package dev.termish.ui

import dev.termish.ssh.SftpEntry
import dev.termish.ssh.SftpSession
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** SFTP 纯逻辑：路径拼接、搜索匹配、目录名清洗、递归下载。 */
class SftpLogicTest {

    // ---------- joinPath ----------

    @Test
    fun joinPathAtRoot() {
        assertEquals("/etc", joinPath("/", "etc"))
        assertEquals("/home", joinPath("/", "home"))
    }

    @Test
    fun joinPathNested() {
        assertEquals("/home/user/file.txt", joinPath("/home/user", "file.txt"))
        assertEquals("/var/log", joinPath("/var", "log"))
    }

    // ---------- matchesQuery / globMatch ----------

    @Test
    fun emptyQueryMatchesEverything() {
        assertTrue(matchesQuery("anything.log", ""))
        assertTrue(matchesQuery("anything.log", "   "))
    }

    @Test
    fun containsMatchIsCaseInsensitive() {
        assertTrue(matchesQuery("nginx.conf", "NGINX"))
        assertTrue(matchesQuery("Nginx.Conf", "nginx"))
        assertFalse(matchesQuery("apache.conf", "nginx"))
    }

    @Test
    fun multipleKeywordsAreAnded() {
        assertTrue(matchesQuery("nginx config", "conf nginx"))
        assertFalse(matchesQuery("nginx config", "conf mysql"))
    }

    @Test
    fun globStarMatchesExtension() {
        assertTrue(matchesQuery("error.log", "*.log"))
        assertFalse(matchesQuery("error.txt", "*.log"))
    }

    @Test
    fun globQuestionMarkMatchesSingleChar() {
        assertTrue(matchesQuery("readme1", "readme?"))
        assertFalse(matchesQuery("readme12", "readme?"))
        assertTrue(matchesQuery("readme2.txt", "readme?.*"))
    }

    @Test
    fun globEscapesRegexMetaCharacters() {
        assertTrue(globMatch("a.b", "a.b"))
        assertFalse(globMatch("axb", "a.b"))
        assertTrue(globMatch("ERROR.LOG", "*.log"))
    }

    // ---------- sanitizeDirName ----------

    @Test
    fun sanitizeDirNameCleansPathAndFallsBack() {
        assertEquals("my_dir", sanitizeDirName("my dir"))
        assertEquals("a_b", sanitizeDirName("a/b"))
        assertEquals("download", sanitizeDirName(""))
        // 空白会按"非法字符"替换成下划线而非视为空（与实现一致）
        assertEquals("___", sanitizeDirName("   "))
    }

    // ---------- downloadDir 递归 ----------

    private fun entry(name: String, dir: Boolean) =
        SftpEntry(name, dir, if (dir) "drwxr-xr-x" else "-rw-r--r--", 0, 0, name.startsWith("."))

    private class FakeFileSink : FileSink {
        val chunks = mutableListOf<ByteArray>()
        var closed = false
        override fun write(bytes: ByteArray) {
            chunks += bytes
        }

        override fun close() {
            closed = true
        }

        fun content(): String = chunks.joinToString("") { it.decodeToString() }
    }

    private class FakeDirSink : DirectorySink {
        val files = mutableMapOf<String, FakeFileSink>()
        override fun openFile(relativePath: String): FileSink =
            FakeFileSink().also { files[relativePath] = it }

        override fun close() {}
    }

    private class FakeSftp(
        val tree: Map<String, List<SftpEntry>>,
        var failPath: String? = null,
    ) : SftpSession {
        val downloaded = mutableListOf<String>()
        override fun list(path: String): List<SftpEntry> = tree[path] ?: emptyList()
        override fun mkdir(path: String) {}
        override fun home(): String = "/"
        override fun upload(
            remotePath: String,
            totalSize: Long,
            onProgress: (sent: Long, total: Long) -> Unit,
            nextChunk: () -> ByteArray?,
        ) {
        }
        override fun download(
            remotePath: String,
            onProgress: (loaded: Long, total: Long) -> Unit,
            onChunk: (ByteArray) -> Unit,
        ) {
            downloaded += remotePath
            if (remotePath == failPath) throw IllegalStateException("download failed: $remotePath")
            onChunk(remotePath.encodeToByteArray())
            onProgress(1, 1)
        }

        override fun close() {}
    }

    @Test
    fun downloadDirRecursesWithRelativePaths() {
        val sftp = FakeSftp(
            mapOf(
                "/root" to listOf(entry("a.txt", false), entry("sub", true), entry(".hidden", false)),
                "/root/sub" to listOf(entry("b.log", false)),
            ),
        )
        val sink = FakeDirSink()

        downloadDir(sftp, "/root", sink)

        // 目录下载不按 UI 的 showHidden 过滤：隐藏文件也下载，只跳过 . 和 ..
        assertEquals(setOf("a.txt", "sub/b.log", ".hidden"), sink.files.keys)
        assertEquals("/root/a.txt", sink.files["a.txt"]!!.content())
        assertEquals("/root/sub/b.log", sink.files["sub/b.log"]!!.content())
        assertEquals("/root/.hidden", sink.files[".hidden"]!!.content())
        assertTrue(sink.files.values.all { it.closed })
    }

    @Test
    fun downloadDirSkipsDotAndDotDot() {
        val sftp = FakeSftp(
            mapOf(
                "/root" to listOf(entry(".", true), entry("..", true), entry("f", false)),
            ),
        )
        val sink = FakeDirSink()

        downloadDir(sftp, "/root", sink)

        assertEquals(setOf("f"), sink.files.keys)
    }

    @Test
    fun downloadDirPropagatesErrorAndClosesFile() {
        val sftp = FakeSftp(
            mapOf(
                "/root" to listOf(entry("a.txt", false), entry("sub", true)),
                "/root/sub" to listOf(entry("b.log", false)),
            ),
            failPath = "/root/a.txt",
        )
        val sink = FakeDirSink()

        assertFailsWith<IllegalStateException> { downloadDir(sftp, "/root", sink) }

        // 失败文件已 close；异常沿调用栈传播，后续目录不再下载
        assertTrue(sink.files["a.txt"]!!.closed)
        assertFalse("sub/b.log" in sink.files)
    }

    // ---------- searchRecursive 递归 ----------

    @Test
    fun searchRecursiveFindsMatchesAcrossDirectories() = runBlocking {
        val sftp = FakeSftp(
            mapOf(
                "/root" to listOf(entry("app-release.apk", false), entry("sub", true)),
                "/root/sub" to listOf(entry("app-debug.apk", false), entry("readme.txt", false)),
            ),
        )
        val hits = searchRecursive(sftp, "/root", "apk")
        assertEquals(setOf("app-release.apk", "sub/app-debug.apk"), hits.map { it.relPath }.toSet())
        assertEquals(2, hits.size)
    }

    @Test
    fun searchRecursiveTraversesHiddenDirs() = runBlocking {
        val sftp = FakeSftp(
            mapOf(
                "/root" to listOf(entry(".hidden", true), entry("note.txt", false)),
                "/root/.hidden" to listOf(entry("secret.apk", false)),
            ),
        )
        // 递归搜索不看 UI 的 showHidden：隐藏目录也遍历（与递归下载目录语义一致）
        val hits = searchRecursive(sftp, "/root", "apk")
        assertEquals(listOf("secret.apk"), hits.map { it.name })
    }

    @Test
    fun searchRecursiveRespectsMaxResults() = runBlocking {
        val sftp = FakeSftp(
            mapOf(
                "/root" to listOf(entry("a.apk", false), entry("b.apk", false), entry("c.apk", false)),
            ),
        )
        val hits = searchRecursive(sftp, "/root", "apk", maxResults = 2)
        assertEquals(2, hits.size)
    }

    @Test
    fun searchRecursiveToleratesUnlistableDir() = runBlocking {
        // FakeSftp 对未知路径返回 emptyList（模拟权限不足目录）：不抛异常、不中断遍历
        val sftp = FakeSftp(
            mapOf(
                "/root" to listOf(entry("ok.apk", false), entry("broken", true)),
            ),
        )
        val hits = searchRecursive(sftp, "/root", "apk")
        assertEquals(listOf("ok.apk"), hits.map { it.name })
    }
}

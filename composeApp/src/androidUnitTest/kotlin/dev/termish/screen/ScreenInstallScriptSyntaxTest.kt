package dev.termish.screen

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

class ScreenInstallScriptSyntaxTest {
    @Test
    fun `legacy cleanup removes only known scripts and caches and is repeatable`() {
        val directory = Files.createTempDirectory("termish-screen-cleanup-").toFile()
        try {
            val obsolete = listOf("screen-relay.py", "screen_service_config.py", "__pycache__/screen-relay.cpython-314.pyc", "__pycache__/screen_service_config.cpython-314.pyc")
            val retained = listOf("screen-service.json", "screen-service.NOTICE", "Termish Helper.app/Contents/MacOS/Termish Helper", "custom.py", "__pycache__/custom.pyc")
            (obsolete + retained).forEach { path ->
                File(directory, path).apply { parentFile.mkdirs() }.writeText(path)
            }
            repeat(2) { runCleanup(directory) }
            obsolete.forEach { assertFalse(File(directory, it).exists(), it) }
            retained.forEach { assertEquals(it, File(directory, it).readText()) }
            File(directory, "__pycache__/custom.pyc").delete()
            runCleanup(directory)
            assertFalse(File(directory, "__pycache__").exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `legacy cleanup does not follow a cache directory symlink`() {
        val directory = Files.createTempDirectory("termish-screen-symlink-").toFile()
        val external = Files.createTempDirectory("termish-unrelated-cache-").toFile()
        try {
            val cache = File(external, "screen-relay.cpython-314.pyc").apply { writeText("keep") }
            Files.createSymbolicLink(File(directory, "__pycache__").toPath(), external.toPath())
            runCleanup(directory)
            assertEquals("keep", cache.readText())
            assertTrue(Files.isSymbolicLink(File(directory, "__pycache__").toPath()))
        } finally {
            Files.deleteIfExists(File(directory, "__pycache__").toPath())
            directory.deleteRecursively()
            external.deleteRecursively()
        }
    }

    @Test
    fun `installer cleanup succeeds through zsh with absent and partially matched caches`() {
        val zsh = File("/bin/zsh")
        assumeTrue("zsh unavailable on this test host", zsh.canExecute())
        val directory = Files.createTempDirectory("termish-screen-zsh-\u4e2d\u6587-'-$-").toFile()
        try {
            // Fresh installation: neither cache glob matches.
            runCleanup(directory, zsh.absolutePath)
            val cacheDirectory = File(directory, "__pycache__").apply { mkdirs() }
            val cache = File(cacheDirectory, "screen-relay.cpython-314.pyc")
            cache.writeText("obsolete")
            // Upgrade: only one of the two cache globs matches.
            runCleanup(directory, zsh.absolutePath)
            assertFalse(cache.exists())
            assertFalse(cacheDirectory.exists())
            runCleanup(directory, zsh.absolutePath)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `installer command preserves stdin and collects final errors without shell expansion`() {
        val literal = "quote ' dollar ${'$'}HOME backtick `uname`"
        val script = "read -r input\nprintf '%s\\n' \"${'$'}input\" ${screenShellQuote(literal)}\nprintf 'final error\\n' >&2\nexit 7"
        val process = ProcessBuilder("/bin/sh", "-c", screenInstallCommand(script)).start()
        process.outputStream.bufferedWriter().use { it.write("stdin value\n") }
        val output = process.inputStream.bufferedReader().readText()
        val error = process.errorStream.bufferedReader().readText()
        assertEquals(7, process.waitFor())
        assertEquals("stdin value\n$literal\nfinal error\n", output)
        assertEquals("", error)
    }

    private fun runCleanup(
        directory: File,
        shell: String = "/bin/sh",
    ) {
        val function = "cleanup_legacy_screen_files() {" + ScreenSession.INSTALL_SCRIPT.substringAfter("cleanup_legacy_screen_files() {").substringBefore("\n}\n") + "\n}\ncleanup_legacy_screen_files"
        val process =
            ProcessBuilder(shell, "-ec", screenInstallCommand("set -e\n$function\necho TERMISH_SCREEN_OK"))
                .redirectErrorStream(true)
                .apply {
                    environment()["APP_DIR"] = directory.absolutePath
                }.start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), output)
        assertEquals("TERMISH_SCREEN_OK\n", output)
    }

    @Test
    fun `generated remote installer is valid POSIX shell`() {
        val script = Files.createTempFile("termish-screen-install-", ".sh")
        try {
            Files.write(script, ScreenSession.INSTALL_SCRIPT.encodeToByteArray())
            val process = ProcessBuilder("/bin/sh", "-n", script.toString()).start()
            val stderr = process.errorStream.bufferedReader().readText()

            assertEquals(0, process.waitFor(), stderr)
        } finally {
            Files.deleteIfExists(script)
        }
    }

    @Test
    fun `packaged native service reports version without desktop dependencies`() {
        val os = if (System.getProperty("os.name").orEmpty().startsWith("Mac")) "Darwin" else "Linux"
        val artifact = ScreenServiceAssets.binaryFor(os, System.getProperty("os.arch"))
        assumeTrue("Native payload unavailable on this test host", artifact != null)
        assertNotNull(artifact)
        val root = File(checkNotNull(System.getProperty("user.dir"))).let { if (it.name == "composeApp") it.parentFile else it }
        val payload = File(root, "composeApp/src/commonMain/composeResources/files/termish-screen/" + artifact.filename)
        val temporary = Files.createTempDirectory("termish-helper-payload-").toFile()
        try {
            val binary =
                if (os == "Darwin") {
                    val bundle = File(temporary, "Termish Helper.app").apply { mkdirs() }
                    val extract = ProcessBuilder("tar", "-xzf", payload.absolutePath, "-C", bundle.absolutePath).redirectErrorStream(true).start()
                    val extractOutput = extract.inputStream.bufferedReader().readText()
                    assertEquals(0, extract.waitFor(), extractOutput)
                    val verify = ProcessBuilder("codesign", "--verify", "--strict", bundle.absolutePath).redirectErrorStream(true).start()
                    val verifyOutput = verify.inputStream.bufferedReader().readText()
                    assertEquals(0, verify.waitFor(), verifyOutput)
                    File(bundle, "Contents/MacOS/Termish Helper")
                } else {
                    payload
                }
            assertEquals(true, binary.setExecutable(true))
            val process = ProcessBuilder(binary.absolutePath, "--version").redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.waitFor(), output)
            assertEquals(ScreenSession.RELAY_VERSION.toString(), output.trim())
            assertFalse(ScreenSession.INSTALL_SCRIPT.contains("python3"))
        } finally {
            temporary.deleteRecursively()
        }
    }
}

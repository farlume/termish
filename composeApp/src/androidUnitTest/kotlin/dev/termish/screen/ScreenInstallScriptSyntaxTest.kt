package dev.termish.screen

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue

class ScreenInstallScriptSyntaxTest {
    @Test
    fun `macOS migration starts system Python and failed bootstrap restores prior service`() {
        assumeTrue("macOS payload verification required", System.getProperty("os.name").orEmpty().startsWith("Mac"))
        val root = File(checkNotNull(System.getProperty("user.dir"))).let { if (it.name == "composeApp") it.parentFile else it }
        val artifact = checkNotNull(ScreenServiceAssets.binaryFor("Darwin", System.getProperty("os.arch")))
        val payload = File(root, "screenService/build/binaries/${artifact.filename}")
        for (fail in listOf(false, true)) {
            val directory = Files.createTempDirectory("termish-mac-migration-中文-'-$-").toFile().canonicalFile
            try {
                val taskHome = File(directory, "user").apply { mkdirs() }
                val app = File(taskHome, "Library/Application Support/termish").apply { mkdirs() }
                val bin = File(directory, "bin").apply { mkdirs() }

                fun mock(
                    name: String,
                    content: String,
                ) {
                    File(bin, name).apply {
                        writeText("#!/bin/sh\n$content\n")
                        assertTrue(setExecutable(true))
                    }
                }
                mock("launchctl", "if [ \"${'$'}1\" = bootstrap ] && [ -f \"${'$'}TERMISH_TEST_FAIL\" ]; then rm -f \"${'$'}TERMISH_TEST_FAIL\"; exit 1; fi\nif [ \"${'$'}1\" = print ]; then echo 'state = running'; fi\nexit 0")
                mock("lsof", "exit 0")
                mock("sleep", "exit 0")
                mock("ffmpeg", "exit 0")
                val relay = File(app, "screen-relay.py").apply { writeText("# prior Python relay\n") }
                val priorBundle =
                    File(app, "Termish Helper.app/prior").apply {
                        parentFile.mkdirs()
                        writeText("prior bundle")
                    }
                val plist =
                    File(taskHome, "Library/LaunchAgents/dev.termish.screen.plist").apply {
                        parentFile.mkdirs()
                        writeText("prior launch agent")
                    }
                val backend = File(app, "screen-service.backend").apply { writeText("rust\n") }
                val token = File(directory, "token").apply { writeText("a".repeat(64)) }
                File(app, "screen-service.json").writeText(
                    buildJsonObject {
                        put("token_file", token.absolutePath)
                        put("log_file", File(directory, "log").absolutePath)
                        put("encoder_pid_file", File(directory, "encoder.pid").absolutePath)
                        put("stream_config_file", File(directory, "quality").absolutePath)
                    }.toString(),
                )
                val staged = File(app, "screen-service.upload-test").apply { payload.copyTo(this) }
                val failure = File(directory, "fail")
                if (fail) failure.writeText("fail once")
                val script = ScreenSession.INSTALL_SCRIPT.replace("${'$'}HOME", "${'$'}TERMISH_TEST_HOME")
                val process =
                    ProcessBuilder("/bin/sh", "-c", script)
                        .redirectErrorStream(true)
                        .apply {
                            environment()["TERMISH_TEST_HOME"] = taskHome.absolutePath
                            environment()["TERMISH_TEST_FAIL"] = failure.absolutePath
                            environment()["TERMISH_NATIVE_STAGE"] = staged.absolutePath
                            environment()["TERMISH_NATIVE_SHA256"] = artifact.sha256
                            environment()["PATH"] = bin.absolutePath + ":" + environment()["PATH"]
                        }.start()
                val output = process.inputStream.bufferedReader().readText()
                if (fail) {
                    assertTrue(process.waitFor() != 0, output)
                    assertEquals("prior launch agent", plist.readText())
                    assertEquals("# prior Python relay\n", relay.readText())
                    assertEquals("prior bundle", priorBundle.readText())
                    assertEquals("rust\n", backend.readText())
                    assertFalse(output.contains("TERMISH_SCREEN_OK"))
                } else {
                    assertEquals(0, process.waitFor(), output)
                    assertTrue(output.contains("TERMISH_SCREEN_OK"), output)
                    assertTrue(plist.readText().contains("/usr/bin/python3"))
                    assertTrue(plist.readText().contains("screen-relay.py"))
                    assertTrue(relay.readText().contains("VERSION = int(\"${ScreenSession.RELAY_VERSION}\")"))
                    assertEquals("python-macos\n", backend.readText())
                    assertEquals(ScreenSession.RELAY_VERSION.toString(), File(taskHome, ".termish-screen.version").readText().trim())
                    val version = ProcessBuilder("/usr/bin/python3", relay.absolutePath, "--version").start()
                    assertEquals(
                        ScreenSession.RELAY_VERSION.toString(),
                        version.inputStream
                            .bufferedReader()
                            .readText()
                            .trim(),
                    )
                    assertEquals(0, version.waitFor())
                }
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    @Test
    fun `ffmpeg configuration follows package upgrades through a stable symlink`() {
        val directory = Files.createTempDirectory("termish-ffmpeg-upgrade-").toFile().canonicalFile
        try {
            val first = File(directory, "Cellar/ffmpeg/1/bin/ffmpeg")
            val second = File(directory, "Cellar/ffmpeg/2/bin/ffmpeg")
            listOf(first, second).forEachIndexed { index, executable ->
                checkNotNull(executable.parentFile).mkdirs()
                executable.writeText("#!/bin/sh\nprintf '%s' '${index + 1}'\n")
                assertTrue(executable.setExecutable(true))
            }
            val entry = File(directory, "bin/ffmpeg")
            checkNotNull(entry.parentFile).mkdirs()
            Files.createSymbolicLink(entry.toPath(), first.toPath())
            val script = ScreenSession.INSTALL_SCRIPT
            val start = script.indexOf("FF_PATH=")
            val end = script.indexOf("echo \"==> ffmpeg:", start)
            assertTrue(start >= 0 && end > start, "installer FFmpeg path resolver missing")
            val resolver = script.substring(start, end)
            listOf(entry.absolutePath, "bin/ffmpeg").forEach { path ->
                val process =
                    ProcessBuilder("/bin/sh", "-ec", "$resolver\nprintf '%s' \"${'$'}FF_PATH\"")
                        .directory(directory)
                        .redirectErrorStream(true)
                        .apply { environment()["FF"] = path }
                        .start()
                val configured = process.inputStream.bufferedReader().readText()
                assertEquals(0, process.waitFor(), configured)
                assertEquals(entry.absolutePath, configured)
                Files.delete(entry.toPath())
                Files.createSymbolicLink(entry.toPath(), second.toPath())
                first.delete()
                val upgraded = ProcessBuilder(configured).redirectErrorStream(true).start()
                val output = upgraded.inputStream.bufferedReader().readText()
                assertEquals(0, upgraded.waitFor(), output)
                assertEquals("2", output)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

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
            assertTrue(ScreenSession.INSTALL_SCRIPT.contains("'ProgramArguments': ['/usr/bin/python3'"))
        } finally {
            temporary.deleteRecursively()
        }
    }
}

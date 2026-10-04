package dev.termish.screen

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import org.junit.Assume.assumeTrue

class ScreenInstallScriptSyntaxTest {
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

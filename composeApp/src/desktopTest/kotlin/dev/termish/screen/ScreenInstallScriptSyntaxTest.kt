package dev.termish.screen

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class ScreenInstallScriptSyntaxTest {
    @Test
    fun `generated remote installer is valid POSIX shell`() {
        val script = Files.createTempFile("termish-screen-install-", ".sh")
        try {
            Files.writeString(script, ScreenSession.INSTALL_SCRIPT)
            val process = ProcessBuilder("/bin/sh", "-n", script.toString()).start()
            val stderr = process.errorStream.bufferedReader().readText()

            assertEquals(0, process.waitFor(), stderr)
        } finally {
            Files.deleteIfExists(script)
        }
    }
}

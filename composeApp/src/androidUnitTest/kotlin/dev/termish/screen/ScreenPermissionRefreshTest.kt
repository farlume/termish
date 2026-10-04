package dev.termish.screen

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScreenPermissionRefreshTest {
    private fun runRefresh(
        os: String,
        restartFails: Boolean = false,
        portsReady: Boolean = true,
    ): Pair<Int, String> {
        val temporary = Files.createTempDirectory("termish-permission-refresh-").toFile()
        try {
            fun command(
                name: String,
                body: String,
            ) {
                File(temporary, name).apply {
                    writeText("#!/bin/sh\n$body\n")
                    check(setExecutable(true))
                }
            }
            command("uname", "echo $os")
            command("id", "echo 501")
            command("launchctl", "echo \"\$*\" >> \"\$REFRESH_CALLS\"; exit ${if (restartFails) 1 else 0}")
            command("lsof", "echo \"\$*\" >> \"\$REFRESH_CALLS\"; exit ${if (portsReady) 0 else 1}")
            command("sleep", "exit 0")
            val calls = File(temporary, "calls")
            val process =
                ProcessBuilder("/bin/sh", "-c", ScreenSession.REFRESH_PERMISSIONS_SCRIPT)
                    .redirectErrorStream(true)
                    .apply {
                        environment()["PATH"] = temporary.absolutePath
                        environment()["REFRESH_CALLS"] = calls.absolutePath
                    }.start()
            val output = process.inputStream.bufferedReader().readText()
            val exit = process.waitFor()
            return exit to (output + if (calls.exists()) calls.readText() else "")
        } finally {
            temporary.deleteRecursively()
        }
    }

    @Test
    fun `Mac refresh restarts only its GUI agent and waits for both ports`() {
        val (exit, output) = runRefresh("Darwin")
        assertEquals(0, exit)
        assertTrue(output.contains("SCREEN_PERMISSION_REFRESH_OK"))
        assertTrue(output.contains("kickstart -k gui/501/dev.termish.screen"))
        assertTrue(output.contains("-iTCP:${ScreenSession.SCREEN_PORT}"))
        assertTrue(output.contains("-iTCP:${ScreenSession.SCREEN_TCP_PORT}"))
        assertEquals(1, output.lineSequence().count { it.startsWith("kickstart") })
    }

    @Test
    fun `restart failure and unavailable ports do not report success`() {
        for ((restartFails, portsReady) in listOf(true to true, false to false)) {
            val (exit, output) = runRefresh("Darwin", restartFails, portsReady)
            assertEquals(1, exit)
            assertFalse(output.contains("SCREEN_PERMISSION_REFRESH_OK"))
            assertEquals(1, output.lineSequence().count { it.startsWith("kickstart") })
        }
    }

    @Test
    fun `Linux refresh does not restart any service`() {
        val (exit, output) = runRefresh("Linux")
        assertEquals(0, exit)
        assertEquals("SCREEN_PERMISSION_REFRESH_OK\n", output)
    }
}

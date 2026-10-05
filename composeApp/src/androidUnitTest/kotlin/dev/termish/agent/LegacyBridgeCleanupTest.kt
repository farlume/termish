package dev.termish.agent

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegacyBridgeCleanupTest {
    @Test
    fun `cleanup stops only owned legacy Python daemon and preserves native files and history`() {
        fixture { root ->
            assertEquals(0, runCleanup(root, "stop"))
            assertEquals("888\n", File(root, "signals").readText())
            assertFalse(File(root, ".local/share/termish-agent/current/termish-agent.pyz").exists())
            preserved.forEach { assertEquals(it, File(root, it).readText()) }
            assertEquals(0, runCleanup(root, "absent"))
        }
    }

    @Test
    fun `cleanup preserves legacy executable if daemon does not stop`() {
        fixture { root ->
            assertEquals(1, runCleanup(root, "stuck"))
            assertTrue(File(root, ".local/share/termish-agent/current/termish-agent.pyz").exists())
            assertEquals("888\n", File(root, "signals").readText())
        }
    }

    private val preserved =
        listOf(
            ".local/share/termish-agent/current/termish-agent",
            ".local/share/termish-agent/current/termish-agent.NOTICE",
            ".local/share/termish-agent/data/sessions.db",
            ".local/share/termish-agent/data/sessions/session.json",
            ".local/share/termish-agent/runtime/rust/agent.sock",
        )

    private fun fixture(test: (File) -> Unit) {
        val root = Files.createTempDirectory("termish legacy home ").toFile()
        try {
            (preserved + ".local/share/termish-agent/current/termish-agent.pyz").forEach { path ->
                File(root, path).apply { parentFile.mkdirs() }.writeText(path)
            }
            File(root, "bin/ps").apply {
                parentFile.mkdirs()
                writeText(
                    """
                    #!/bin/sh
                    OWNER=${'$'}(id -u)
                    LEGACY="${'$'}HOME/.local/share/termish-agent/current/termish-agent.pyz"
                    if [ "${'$'}1" = -axo ]; then
                      [ "${'$'}MODE" != absent ] || exit 0
                      printf '%s\n' "888 ${'$'}OWNER /usr/bin/python3 ${'$'}LEGACY serve" "889 ${'$'}OWNER /usr/bin/rust ${'$'}LEGACY serve" "890 999999 /usr/bin/python3 ${'$'}LEGACY serve" "891 ${'$'}OWNER /usr/bin/python3 ${'$'}LEGACY serve" "892 ${'$'}OWNER /usr/bin/python3 /other/agent.pyz serve"
                    else
                      case "${'$'}2:${'$'}4" in
                        888:uid=|889:uid=|891:uid=) printf '%s\n' "${'$'}OWNER" ;;
                        888:command=)
                          [ ! -f "${'$'}HOME/stopped" ] || exit 0
                          printf '%s\n' "/usr/bin/python3 ${'$'}LEGACY serve" ;;
                        888:comm=) printf '%s\n' /usr/bin/python3 ;;
                        889:command=) printf '%s\n' "/usr/bin/rust ${'$'}LEGACY serve" ;;
                        889:comm=) printf '%s\n' /usr/bin/rust ;;
                        891:command=) printf '%s\n' /usr/bin/unrelated ;;
                        *) exit 1 ;;
                      esac
                    fi
                    """.trimIndent(),
                )
                assertTrue(setExecutable(true))
            }
            test(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun runCleanup(
        root: File,
        mode: String,
    ): Int {
        val wrappers =
            """
            kill() {
              [ "${'$'}1" = -TERM ] && [ "${'$'}2" = 888 ] || exit 99
              printf '%s\n' "${'$'}2" >> "${'$'}HOME/signals"
              [ "${'$'}MODE" != stop ] || touch "${'$'}HOME/stopped"
              return 0
            }
            sleep() { :; }
            """.trimIndent()
        val process =
            ProcessBuilder("/bin/sh", "-c", wrappers + "\n" + legacyBridgeCleanupScript)
                .redirectErrorStream(true)
                .apply {
                    environment()["HOME"] = root.absolutePath
                    environment()["PATH"] = File(root, "bin").absolutePath + ":" + System.getenv("PATH")
                    environment()["MODE"] = mode
                }.start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        assertEquals("", output)
        return exit
    }
}

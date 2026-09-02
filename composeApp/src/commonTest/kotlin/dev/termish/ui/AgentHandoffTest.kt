package dev.termish.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentHandoffTest {
    @Test
    fun terminalContextKeepsTailAndDirectory() {
        val context =
            terminalAgentLaunchContext(
                selection = "first\n" + "x".repeat(13_000) + "\nlast-error",
                directory = " /srv/app ",
                wrapPrompt = { "DATA:\n$it" },
            )

        assertNotNull(context)
        assertEquals("/srv/app", context.directory)
        assertTrue(context.prompt.startsWith("DATA:\n…\n"))
        assertTrue(context.prompt.endsWith("last-error"))
    }

    @Test
    fun fileContextUsesUniqueRemotePaths() {
        val context =
            sftpAgentLaunchContext(
                paths = listOf("/srv/a.log", "/srv/a.log", " /srv/b.log "),
                directory = "/srv",
                wrapPrompt = { it },
            )

        assertNotNull(context)
        assertEquals("- /srv/a.log\n- /srv/b.log", context.prompt)
        assertEquals("/srv", context.directory)
    }

    @Test
    fun blankContextIsIgnored() {
        assertNull(terminalAgentLaunchContext("  ", null) { it })
        assertNull(sftpAgentLaunchContext(emptyList(), "/") { it })
    }
}

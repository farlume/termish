package dev.termish.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class BackNavigationTest {
    @Test
    fun terminalClosesTopLayerBeforeExiting() {
        assertEquals(
            TerminalBackTarget.CLOSE_SCREEN,
            terminalBackTarget(true, true, true, true, true, true),
        )
        assertEquals(
            TerminalBackTarget.CANCEL_VOICE,
            terminalBackTarget(false, true, true, true, true, true),
        )
        assertEquals(
            TerminalBackTarget.CLEAR_SELECTION,
            terminalBackTarget(false, false, false, false, false, true),
        )
        assertEquals(
            TerminalBackTarget.EXIT,
            terminalBackTarget(false, false, false, false, false, false),
        )
    }

    @Test
    fun agentReturnsThroughWorkspaceHierarchy() {
        assertEquals(
            AgentBackTarget.CLOSE_FILE_BROWSER,
            agentBackTarget(false, true, true, true, true),
        )
        assertEquals(
            AgentBackTarget.SHOW_CHAT,
            agentBackTarget(false, false, false, true, true),
        )
        assertEquals(
            AgentBackTarget.SHOW_AGENT_HOME,
            agentBackTarget(false, false, false, false, true),
        )
        assertEquals(
            AgentBackTarget.EXIT,
            agentBackTarget(false, false, false, false, false),
        )
    }

    @Test
    fun sftpClosesPreviewAndSelectionBeforeLeaving() {
        assertEquals(
            SftpBackTarget.CLOSE_PREVIEW,
            sftpBackTarget(true, true, true, true),
        )
        assertEquals(
            SftpBackTarget.CLEAR_SELECTION,
            sftpBackTarget(false, true, true, true),
        )
        assertEquals(
            SftpBackTarget.CLOSE_SEARCH,
            sftpBackTarget(false, false, true, true),
        )
        assertEquals(
            SftpBackTarget.PREVIOUS_DIRECTORY,
            sftpBackTarget(false, false, false, true),
        )
        assertEquals(
            SftpBackTarget.EXIT,
            sftpBackTarget(false, false, false, false),
        )
    }
}

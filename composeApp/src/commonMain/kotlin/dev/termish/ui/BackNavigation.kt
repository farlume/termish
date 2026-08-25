package dev.termish.ui

internal enum class TerminalBackTarget {
    CLOSE_SCREEN,
    CANCEL_VOICE,
    CLOSE_GIT,
    CLOSE_TOOL_MENU,
    CLOSE_SNIPPETS,
    CLEAR_SELECTION,
    EXIT,
}

internal fun terminalBackTarget(
    screenFullscreen: Boolean,
    voiceActive: Boolean,
    gitOpen: Boolean,
    toolMenuOpen: Boolean,
    snippetsOpen: Boolean,
    selectionActive: Boolean,
): TerminalBackTarget =
    when {
        screenFullscreen -> TerminalBackTarget.CLOSE_SCREEN
        voiceActive -> TerminalBackTarget.CANCEL_VOICE
        gitOpen -> TerminalBackTarget.CLOSE_GIT
        toolMenuOpen -> TerminalBackTarget.CLOSE_TOOL_MENU
        snippetsOpen -> TerminalBackTarget.CLOSE_SNIPPETS
        selectionActive -> TerminalBackTarget.CLEAR_SELECTION
        else -> TerminalBackTarget.EXIT
    }

internal enum class AgentBackTarget {
    CLOSE_SCREEN,
    CLOSE_FILE_BROWSER,
    CLOSE_GIT,
    SHOW_CHAT,
    SHOW_AGENT_HOME,
    EXIT,
}

internal fun agentBackTarget(
    screenOpen: Boolean,
    fileBrowserOpen: Boolean,
    gitOpen: Boolean,
    childPageOpen: Boolean,
    sessionOpen: Boolean,
): AgentBackTarget =
    when {
        screenOpen -> AgentBackTarget.CLOSE_SCREEN
        fileBrowserOpen -> AgentBackTarget.CLOSE_FILE_BROWSER
        gitOpen -> AgentBackTarget.CLOSE_GIT
        childPageOpen -> AgentBackTarget.SHOW_CHAT
        sessionOpen -> AgentBackTarget.SHOW_AGENT_HOME
        else -> AgentBackTarget.EXIT
    }

internal enum class SftpBackTarget {
    CLOSE_PREVIEW,
    CLEAR_SELECTION,
    CLOSE_SEARCH,
    PREVIOUS_DIRECTORY,
    EXIT,
}

internal fun sftpBackTarget(
    previewOpen: Boolean,
    selectionActive: Boolean,
    searchOpen: Boolean,
    hasDirectoryHistory: Boolean,
): SftpBackTarget =
    when {
        previewOpen -> SftpBackTarget.CLOSE_PREVIEW
        selectionActive -> SftpBackTarget.CLEAR_SELECTION
        searchOpen -> SftpBackTarget.CLOSE_SEARCH
        hasDirectoryHistory -> SftpBackTarget.PREVIOUS_DIRECTORY
        else -> SftpBackTarget.EXIT
    }

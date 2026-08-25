package dev.termish.ui

import dev.termish.agent.AgentArtifact
import dev.termish.agent.AgentChatMessage
import dev.termish.agent.AgentEventCursor
import dev.termish.agent.AgentEventDecision
import dev.termish.agent.AgentPromptEventBuffer
import dev.termish.agent.classifyAgentEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class AgentTurnTest {
    @Test
    fun legacyMessagesCreateUniqueTurns() {
        val turns =
            buildAgentTurns(
                listOf(
                    message("assistant", "orphan answer"),
                    message("user", "first question"),
                    message("assistant", "first answer"),
                    message("user", "second question"),
                    message("assistant", "second answer"),
                ),
                busy = false,
            )

        assertEquals(listOf("legacy-0", "legacy-1", "legacy-3"), turns.map { it.id })
        assertEquals(listOf("orphan answer"), turns[0].events.map { it.text })
        assertEquals("first question", turns[1].user?.text)
        assertEquals(listOf("first answer"), turns[1].events.map { it.text })
    }

    @Test
    fun protocolTurnsGroupThinkingToolsAndAnswer() {
        val turns =
            buildAgentTurns(
                listOf(
                    message("user", "question", turnId = "turn-1"),
                    message("thinking", "checking", turnId = "turn-1"),
                    message("tool", "result", turnId = "turn-1"),
                    message("assistant", "answer", turnId = "turn-1"),
                ),
                busy = false,
            )

        assertEquals(1, turns.size)
        assertEquals(listOf("thinking", "tool", "assistant"), turns.single().events.map { it.role })
    }

    @Test
    fun earlyBridgeEventAndOptimisticUserMessageShareOneTurn() {
        val turns =
            buildAgentTurns(
                listOf(
                    message("thinking", "", turnId = "turn-1"),
                    message("user", "question", turnId = "turn-1"),
                ),
                busy = true,
            )

        assertEquals(1, turns.size)
        assertEquals("question", turns.single().user?.text)
        assertEquals(listOf("thinking"), turns.single().events.map { it.role })
    }

    @Test
    fun intermediateRepliesSplitActivityGroupsWithoutChangingOrder() {
        val events =
            listOf(
                message("thinking", "first thought"),
                message("tool", "first result"),
                message("assistant", "progress update"),
                message("thinking", "second thought"),
                message("tool", "second result"),
                message("assistant", "final answer"),
            )

        val blocks = buildAgentTurnBlocks(events, showThinking = true)

        assertEquals(4, blocks.size)
        assertEquals(listOf("thinking", "tool"), (blocks[0] as AgentActivityBlock).activities.map { it.role })
        assertEquals("progress update", (blocks[1] as AgentMessageBlock).message.text)
        assertEquals(listOf("thinking", "tool"), (blocks[2] as AgentActivityBlock).activities.map { it.role })
        assertEquals("final answer", (blocks[3] as AgentMessageBlock).message.text)
    }

    @Test
    fun hiddenThinkingDoesNotMergeToolsAcrossAgentReply() {
        val events =
            listOf(
                message("thinking", "hidden"),
                message("tool", "result"),
                message("assistant", "update"),
            )

        val blocks = buildAgentTurnBlocks(events, showThinking = false)

        assertEquals(2, blocks.size)
        assertEquals(listOf("tool"), (blocks[0] as AgentActivityBlock).activities.map { it.role })
        assertEquals("update", (blocks[1] as AgentMessageBlock).message.text)
    }

    @Test
    fun completedToolKeepsLatestTurnRunningUntilAgentSettles() {
        val turns =
            buildAgentTurns(
                listOf(
                    message("user", "question", turnId = "turn-1"),
                    message("tool", "completed output", turnId = "turn-1"),
                ),
                busy = true,
            )

        assertEquals(true, turns.single().running)
    }

    @Test
    fun statusOnlyThinkingPhaseRemainsInActivityTimeline() {
        val blocks =
            buildAgentTurnBlocks(
                listOf(
                    message("thinking", ""),
                    message("tool", "completed output"),
                ),
                showThinking = true,
            )

        assertEquals(listOf("thinking", "tool"), (blocks.single() as AgentActivityBlock).activities.map { it.role })
    }

    @Test
    fun typewriterStepDoesNotSplitEmojiSurrogatePair() {
        val text = "abcd😀next"

        assertEquals(6, nextTypewriterIndex(text, 0, charsPerFrame = 5))
    }

    @Test
    fun typewriterUsesSingleCharactersForShortBacklogAndCatchesUpLongBacklog() {
        assertEquals(1, typewriterCharsPerFrame(40))
        assertEquals(4, typewriterCharsPerFrame(200))
        assertEquals(12, typewriterCharsPerFrame(500))
        assertEquals(24, typewriterCharsPerFrame(1_000))
        assertEquals(24, typewriterCharsPerFrame(2_000))
        assertEquals(48, typewriterCharsPerFrame(3_000))
    }

    @Test
    fun historyNeverAnimatesWithoutALocallyStartedTurn() {
        assertFalse(shouldAnimateAgentTurn("turn-1", null))
        assertFalse(shouldAnimateAgentTurn("turn-1", "turn-2"))
        assertTrue(shouldAnimateAgentTurn("turn-1", "turn-1"))
    }

    @Test
    fun outputFollowResumesOnlyAfterScrollingSettlesAtBottom() {
        assertFalse(shouldResumeAgentOutputFollow(isScrollInProgress = true, canScrollForward = false))
        assertFalse(shouldResumeAgentOutputFollow(isScrollInProgress = false, canScrollForward = true))
        assertTrue(shouldResumeAgentOutputFollow(isScrollInProgress = false, canScrollForward = false))
    }

    @Test
    fun slashCommandSuggestionsFollowTheSelectedAgent() {
        assertEquals(listOf("/model"), slashCommandSuggestions("codex", "/mo"))
        assertEquals(listOf("/settings"), slashCommandSuggestions("pi", "/set"))
    }

    @Test
    fun slashCommandMenuClosesAfterArgumentsBegin() {
        assertEquals(emptyList(), slashCommandSuggestions("codex", "/model gpt-5"))
        assertEquals(emptyList(), slashCommandSuggestions("codex", "review /model"))
    }

    @Test
    fun slashCommandsResolveToRealLocalActions() {
        assertEquals(AgentSlashAction.MODEL, agentSlashAction("/model"))
        assertEquals(AgentSlashAction.NEW, agentSlashAction("/clear"))
        assertEquals(AgentSlashAction.SETTINGS, agentSlashAction("/settings"))
        assertEquals(AgentSlashAction.UNSUPPORTED, agentSlashAction("/compact"))
    }

    @Test
    fun voiceDictationPreservesTheExistingDraft() {
        assertEquals("帮我 检查这个项目", mergeAgentVoiceDraft("帮我", "检查这个项目"))
        assertEquals("帮我 检查这个项目", mergeAgentVoiceDraft("帮我 ", " 检查这个项目 "))
        assertEquals("检查这个项目", mergeAgentVoiceDraft("", " 检查这个项目 "))
        assertEquals("帮我", mergeAgentVoiceDraft("帮我", "  "))
    }

    @Test
    fun agentEventCursorClassifiesDuplicatesGapsAndBridgeRestarts() {
        val cursor = AgentEventCursor("epoch-a", 8)

        assertEquals(AgentEventDecision.APPLY, classifyAgentEvent(cursor, "epoch-a", 9))
        assertEquals(AgentEventDecision.DUPLICATE, classifyAgentEvent(cursor, "epoch-a", 8))
        assertEquals(AgentEventDecision.GAP, classifyAgentEvent(cursor, "epoch-a", 11))
        assertEquals(AgentEventDecision.RESET, classifyAgentEvent(cursor, "epoch-b", 1))
        assertEquals(AgentEventDecision.RESET, classifyAgentEvent(null, "epoch-a", 1))
        assertEquals(AgentEventDecision.APPLY, classifyAgentEvent(cursor, "", 0))
    }

    @Test
    fun promptEventBufferHoldsOnlyTheOpenSessionAndPreservesOrder() {
        val buffer = AgentPromptEventBuffer()
        val first = buildJsonObject { put("eventSeq", 7) }
        val second = buildJsonObject { put("eventSeq", 8) }

        buffer.begin("session-a")
        assertTrue(buffer.enqueue("session-a", first))
        assertFalse(buffer.enqueue("session-b", second))
        assertTrue(buffer.enqueue("session-a", second))
        assertEquals(listOf(first, second), buffer.drain("session-a"))
        assertFalse(buffer.enqueue("session-a", first))
    }

    private fun message(
        role: String,
        text: String,
        turnId: String = "",
    ) = AgentChatMessage(role = role, text = text, turnId = turnId)

    @Test
    fun extractToolCommandParsesBashJson() {
        val input = """{"command": "ls -la", "cwd": "/tmp"}"""
        assertEquals("ls -la", extractToolCommand(input))
    }

    @Test
    fun extractToolCommandSupportsCmdAlias() {
        assertEquals("pwd", extractToolCommand("""{"cmd": "pwd"}"""))
    }

    @Test
    fun extractToolCommandReturnsNullForNonJson() {
        assertNull(extractToolCommand("plain text input"))
        assertNull(extractToolCommand(""))
    }

    @Test
    fun extractToolSummaryPrefersCommandThenPath() {
        assertEquals("ls -la", extractToolSummary("""{"command": "ls -la", "cwd": "/tmp"}"""))
        assertEquals("/home/user/proj", extractToolSummary("""{"path": "/home/user/proj", "recursive": true}"""))
        assertEquals("https://example.com", extractToolSummary("""{"url": "https://example.com"}"""))
    }

    @Test
    fun extractToolSummaryFallsBackToFirstLine() {
        assertEquals("ls -la", extractToolSummary("ls -la\n--all"))
    }

    @Test
    fun removeCommandFromInputKeepsRemainingArgs() {
        val out = removeCommandFromInput("""{"command": "ls", "cwd": "/tmp"}""")
        assertTrue(out.contains("cwd"))
        assertFalse(out.contains("command"))
    }

    @Test
    fun removeCommandFromInputReturnsInputForNonJson() {
        assertEquals("plain", removeCommandFromInput("plain"))
    }

    @Test
    fun agentToolMetaMapsToolTypes() {
        assertEquals(AgentToolKind.COMMAND, agentToolMeta("bash").kind)
        assertEquals(AgentToolKind.FILE_LIST, agentToolMeta("list_files").kind)
        assertEquals(AgentToolKind.READ_FILE, agentToolMeta("read_file").kind)
        assertEquals(AgentToolKind.NETWORK, agentToolMeta("web_fetch").kind)
        assertEquals(AgentToolKind.TOOL, agentToolMeta("unknown_tool_x").kind)
    }

    @Test
    fun formatAgentDurationCoversUnits() {
        assertEquals("500ms", formatAgentDuration(500))
        assertEquals("1s", formatAgentDuration(1_200))
        assertEquals("2s", formatAgentDuration(2_400))
        assertEquals("1m 5s", formatAgentDuration(65_000))
        assertEquals("2m 0s", formatAgentDuration(119_999))
    }

    @Test
    fun artifactsAreAggregatedAcrossToolsWithoutDuplicates() {
        val deck = AgentArtifact("deck.pptx", "/work/deck.pptx", 42, "presentation")
        val sheet = AgentArtifact("data.xlsx", "/work/data.xlsx", 24, "spreadsheet")
        val events =
            listOf(
                AgentChatMessage(role = "tool", text = "", artifacts = listOf(deck)),
                AgentChatMessage(role = "assistant", text = "done"),
                AgentChatMessage(role = "tool", text = "", artifacts = listOf(deck, sheet)),
            )

        assertEquals(listOf(deck, sheet), collectAgentArtifacts(events))
    }
}

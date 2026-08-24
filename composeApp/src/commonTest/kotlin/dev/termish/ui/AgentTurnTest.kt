package dev.termish.ui

import dev.termish.agent.AgentChatMessage
import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun typewriterAdaptsBatchSizeToBacklog() {
        assertEquals(2, typewriterCharsPerFrame(40))
        assertEquals(4, typewriterCharsPerFrame(200))
        assertEquals(8, typewriterCharsPerFrame(500))
        assertEquals(16, typewriterCharsPerFrame(1_000))
        assertEquals(32, typewriterCharsPerFrame(2_000))
    }

    private fun message(
        role: String,
        text: String,
        turnId: String = "",
    ) = AgentChatMessage(role = role, text = text, turnId = turnId)
}

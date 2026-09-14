package com.anomalyco.opencode.ui.chat

import com.anomalyco.opencode.domain.error.OpenCodeError
import com.anomalyco.opencode.domain.model.ChatMessage
import com.anomalyco.opencode.domain.model.MessagePart
import com.anomalyco.opencode.domain.model.MessageRole
import com.anomalyco.opencode.domain.model.StepState
import com.anomalyco.opencode.domain.model.StreamEvent
import com.anomalyco.opencode.domain.model.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Object-level coverage of the streaming projection rules: append vs merge by
 * part id, upsert by call/step id, and rotation of the live bubble.
 */
class MessageAssemblerTest {

    private val session = "s1"
    private val prior = ChatMessage(
        id = "m0",
        sessionId = session,
        role = MessageRole.USER,
        parts = listOf(MessagePart.TextPart("question", id = "tp0")),
    )

    private fun live(messages: List<ChatMessage>) =
        messages.single { it.id == MessageAssembler.liveMessageId(session) }

    @Test
    fun `text deltas merge into one part by id and open a live assistant bubble`() {
        var messages = listOf(prior)
        messages = MessageAssembler.apply(messages, session, StreamEvent.TextDelta(session, "p1", "He"))
        messages = MessageAssembler.apply(messages, session, StreamEvent.TextDelta(session, "p1", "llo"))
        messages = MessageAssembler.apply(messages, session, StreamEvent.TextDelta(session, "p2", "!"))

        val liveMsg = live(messages)
        assertEquals(MessageRole.ASSISTANT, liveMsg.role)
        assertEquals(
            listOf(
                MessagePart.TextPart("Hello", id = "p1"),
                MessagePart.TextPart("!", id = "p2"),
            ),
            liveMsg.parts,
        )
    }

    @Test
    fun `reasoning and text parts keep independent streams`() {
        var messages = emptyList<ChatMessage>()
        messages = MessageAssembler.apply(messages, session, StreamEvent.ReasoningDelta(session, "r1", "hmm"))
        messages = MessageAssembler.apply(messages, session, StreamEvent.TextDelta(session, "t1", "answer"))
        messages = MessageAssembler.apply(messages, session, StreamEvent.ReasoningDelta(session, "r1", " mhm"))

        val parts = live(messages).parts
        assertEquals(
            MessagePart.ReasoningPart("hmm mhm", isFinished = false, id = "r1"),
            parts.first { it is MessagePart.ReasoningPart },
        )
        assertEquals(
            MessagePart.TextPart("answer", id = "t1"),
            parts.first { it is MessagePart.TextPart },
        )
    }

    @Test
    fun `tool lifecycle upserts the single matching call id`() {
        var messages = emptyList<ChatMessage>()
        messages = MessageAssembler.apply(
            messages, session,
            StreamEvent.ToolCalled(session, "c1", "read", """{"path":"a"}"""),
        )
        messages = MessageAssembler.apply(
            messages, session,
            StreamEvent.ToolUpdated(session, "c1", null, ToolStatus.RUNNING, "partial"),
        )
        messages = MessageAssembler.apply(
            messages, session,
            StreamEvent.ToolFinished(session, "c1", ToolStatus.COMPLETED, "done"),
        )

        val tools = live(messages).parts.filterIsInstance<MessagePart.ToolCallPart>()
        assertEquals(1, tools.size)
        assertEquals("c1", tools.single().callId)
        assertEquals(ToolStatus.COMPLETED, tools.single().status)
    }

    @Test
    fun `step started and finished upsert one step part`() {
        var messages = emptyList<ChatMessage>()
        messages = MessageAssembler.apply(messages, session, StreamEvent.StepStarted(session, "st", "planning"))
        messages = MessageAssembler.apply(messages, session, StreamEvent.StepFinished(session, "st", "planning"))

        val steps = live(messages).parts.filterIsInstance<MessagePart.StepPart>()
        assertEquals(1, steps.size)
        assertEquals(StepState.COMPLETED, steps.single().state)
    }

    @Test
    fun `session idle marks reasoning finished and leaves transcript untouched otherwise`() {
        var messages = listOf(prior)
        messages = MessageAssembler.apply(messages, session, StreamEvent.ReasoningDelta(session, "r1", "x"))
        messages = MessageAssembler.apply(messages, session, StreamEvent.SessionIdle(session))

        val reasoning = live(messages).parts.single() as MessagePart.ReasoningPart
        assertTrue(reasoning.isFinished)
    }

    @Test
    fun `streaming accumulation is NOT capped - the part limit guards history retention only`() {
        // A single delta larger than MAX_PART_CHARS must pass through the
        // live assembler untouched: generation is never cut mid-stream.
        val big = "s".repeat(com.anomalyco.opencode.data.PayloadLimits.MAX_PART_CHARS + 1000)

        val messages = MessageAssembler.apply(emptyList(), session, StreamEvent.TextDelta(session, "p1", big))

        val part = messages.single().parts.single() as MessagePart.TextPart
        assertEquals(big.length, part.content.length)
        org.junit.Assert.assertFalse(part.contentTruncated)
    }

    @Test
    fun `tool-called truncation flag lands on the live part`() {
        val event = StreamEvent.ToolCalled(session, "c1", "edit", "x".repeat(64), argsTruncated = true)
        val parts = MessageAssembler.apply(emptyList(), session, event).single().parts
        val part = parts.single() as MessagePart.ToolCallPart
        assertTrue(part.argsTruncated)
    }

    @Test
    fun `message updates, errors, interactions and unknown events are not merged into the transcript`() {
        val messages = listOf(prior)
        listOf(
            StreamEvent.MessageUpdated(session, "m9"),
            StreamEvent.SessionError(session, OpenCodeError.ServerNarrative("boom")),
            StreamEvent.Unknown("session.next.brand.thing"),
            StreamEvent.PermissionAsked(
                session,
                com.anomalyco.opencode.domain.model.PermissionRequest(requestId = "p1"),
            ),
            StreamEvent.QuestionAsked(
                session,
                com.anomalyco.opencode.domain.model.QuestionRequest(questionId = "q1"),
            ),
        ).forEach { event ->
            assertEquals(messages, MessageAssembler.apply(messages, session, event))
        }
    }

    @Test
    fun `blank part ids merge into the trailing same-kind part instead of a shared empty id`() {
        var messages = emptyList<ChatMessage>()
        // Server omits partID entirely.
        messages = MessageAssembler.apply(messages, session, StreamEvent.TextDelta(session, "", "al"))
        messages = MessageAssembler.apply(messages, session, StreamEvent.TextDelta(session, "", "pha"))
        val textParts = live(messages).parts.filterIsInstance<MessagePart.TextPart>()
        assertEquals(1, textParts.size)
        assertEquals("alpha", textParts.single().content)

        // A keyed part still opens its own bubble.
        messages = MessageAssembler.apply(messages, session, StreamEvent.TextDelta(session, "p2", "!"))
        assertEquals(2, live(messages).parts.size)

        // An unkeyed delta continues the LAST part, never a keyed-"" collapse.
        messages = MessageAssembler.apply(messages, session, StreamEvent.TextDelta(session, "", "?"))
        assertEquals(
            listOf("alpha", "!?"),
            live(messages).parts.filterIsInstance<MessagePart.TextPart>().map { it.content },
        )
    }

    @Test
    fun `blank-id reasoning continues its own tail and never joins a text part`() {
        var messages = emptyList<ChatMessage>()
        messages = MessageAssembler.apply(messages, session, StreamEvent.TextDelta(session, "", "answer"))
        messages = MessageAssembler.apply(messages, session, StreamEvent.ReasoningDelta(session, "", "think"))
        messages = MessageAssembler.apply(messages, session, StreamEvent.ReasoningDelta(session, "", "ing"))

        val parts = live(messages).parts
        assertEquals(2, parts.size)
        assertEquals("answer", (parts[0] as MessagePart.TextPart).content)
        assertEquals("thinking", (parts[1] as MessagePart.ReasoningPart).thinking)
    }

    @Test
    fun `explicit part ids still create distinct parts and never touch the tail`() {
        var messages = emptyList<ChatMessage>()
        messages = MessageAssembler.apply(messages, session, StreamEvent.TextDelta(session, "a", "1"))
        messages = MessageAssembler.apply(messages, session, StreamEvent.TextDelta(session, "b", "2"))
        messages = MessageAssembler.apply(messages, session, StreamEvent.TextDelta(session, "a", "3"))

        val parts = live(messages).parts.filterIsInstance<MessagePart.TextPart>()
        assertEquals(listOf("13", "2"), parts.map { it.content })
        assertEquals(listOf("a", "b"), parts.map { it.id })
    }

    @Test
    fun `rotateLiveMessage renames the live bubble so the next turn starts fresh`() {
        var messages = listOf(prior)
        messages = MessageAssembler.apply(messages, session, StreamEvent.TextDelta(session, "p1", "one"))
        messages = MessageAssembler.rotateLiveMessage(messages, session, newId = "assistant-1")

        assertFalse(messages.any { it.id == MessageAssembler.liveMessageId(session) })
        assertTrue(messages.any { it.id == "assistant-1" })

        // Next delta opens a *new* live bubble instead of touching "assistant-1".
        messages = MessageAssembler.apply(messages, session, StreamEvent.TextDelta(session, "p1", "two"))
        assertEquals("one", messages.single { it.id == "assistant-1" }.parts.filterIsInstance<MessagePart.TextPart>().single().content)
        assertEquals("two", live(messages).parts.filterIsInstance<MessagePart.TextPart>().single().content)
    }
}

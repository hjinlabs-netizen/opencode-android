package com.anomalyco.opencode.domain.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Polymorphic (type-discriminated) serialization contract for message parts:
 * round-trip fidelity and tolerance of fields introduced by newer servers.
 */
class MessagePartPolymorphicTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
    }

    private val parts: List<MessagePart> = listOf(
        MessagePart.TextPart("hello"),
        MessagePart.ReasoningPart("deep thought", isFinished = true),
        MessagePart.ToolCallPart("call-1", "edit", """{"path":"a.kt"}""", ToolStatus.RUNNING),
        MessagePart.ShellPart("ls -la", "total 0", exitCode = 0),
        MessagePart.StepPart("step-1", "calling tool", StepState.COMPLETED),
    )

    @Test
    fun `round-trips a heterogeneous part list via the type discriminator`() {
        val encoded = json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(MessagePart.serializer()),
            parts,
        )
        assertTrue(encoded.contains(""""type":"text""""))
        assertTrue(encoded.contains(""""type":"tool""""))

        val decoded = json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(MessagePart.serializer()),
            encoded,
        )
        assertEquals(parts, decoded)
    }

    @Test
    fun `unknown fields on the wire never crash known parts`() {
        val wire = """
            [
              {"type":"text","content":"hi","x-future":{"a":1},"score":0.42},
              {"type":"tool","callId":"c","toolName":"bash","args":"{}","status":"COMPLETED","brandNew":"x"}
            ]
        """.trimIndent()
        val decoded = json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(MessagePart.serializer()),
            wire,
        )
        assertEquals(
            listOf(
                MessagePart.TextPart("hi"),
                MessagePart.ToolCallPart("c", "bash", "{}", ToolStatus.COMPLETED),
            ),
            decoded,
        )
    }

    @Test
    fun `omitted optional fields fall back to defaults`() {
        val decoded = json.decodeFromString(
            MessagePart.ReasoningPart.serializer(),
            """{"thinking":"partial"}""",
        )
        assertEquals(MessagePart.ReasoningPart("partial", isFinished = false), decoded)
    }

    @Test
    fun `M5 truncation flags round-trip and legacy payloads default to false`() {
        val flagged = MessagePart.TextPart("cut", "p1", contentTruncated = true)
        val encoded = json.encodeToString(MessagePart.TextPart.serializer(), flagged)
        assertEquals(flagged, json.decodeFromString(MessagePart.TextPart.serializer(), encoded))

        // A payload written BEFORE the flag existed decodes cleanly.
        val legacy = json.decodeFromString(
            MessagePart.ReasoningPart.serializer(),
            """{"thinking":"old wire shape","isFinished":true,"id":"p2"}""",
        )
        assertEquals(false, legacy.thinkingTruncated)
        assertEquals("old wire shape", legacy.thinking)
    }

    @Test
    fun `tool and step status enums map wire vocabulary`() {
        assertEquals(ToolStatus.COMPLETED, ToolStatus.fromWire("completed"))
        assertEquals(ToolStatus.COMPLETED, ToolStatus.fromWire("success"))
        assertEquals(ToolStatus.RUNNING, ToolStatus.fromWire("in_progress"))
        assertEquals(ToolStatus.ERROR, ToolStatus.fromWire("failed"))
        assertEquals(ToolStatus.PENDING, ToolStatus.fromWire(null))
        assertEquals(ToolStatus.PENDING, ToolStatus.fromWire("weird"))

        assertEquals(StepState.COMPLETED, StepState.fromWire("finished"))
        assertEquals(StepState.IN_PROGRESS, StepState.fromWire("active"))
        assertEquals(StepState.FAILED, StepState.fromWire("error"))
        assertEquals(StepState.STARTED, StepState.fromWire("brand-new-value"))
    }

    @Test
    fun `message roles map case-insensitively with user fallback`() {
        assertEquals(MessageRole.USER, MessageRole.fromWire("user"))
        assertEquals(MessageRole.ASSISTANT, MessageRole.fromWire("Assistant"))
        assertEquals(MessageRole.SYSTEM, MessageRole.fromWire("system"))
        assertEquals(MessageRole.USER, MessageRole.fromWire("mystery"))
        assertEquals(MessageRole.USER, MessageRole.fromWire(null))
    }
}

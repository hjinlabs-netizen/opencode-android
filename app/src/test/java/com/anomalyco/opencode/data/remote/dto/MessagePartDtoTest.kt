package com.anomalyco.opencode.data.remote.dto

import com.anomalyco.opencode.data.PayloadLimits
import com.anomalyco.opencode.domain.model.MessagePart
import com.anomalyco.opencode.util.testJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sprint 1b history-decode caps: oversized tool args / shell outputs are cut
 * at the approved limits WITH a typed flag (no marker text is authored here —
 * the UI renders the localized notice from the flag).
 */
class MessagePartDtoTest {

    private fun part(raw: String): MessagePart? =
        testJson().decodeFromString(PartDto.serializer(), raw).toDomain()

    @Test
    fun `oversized tool args are capped and flagged`() {
        val huge = "a".repeat(PayloadLimits.MAX_TOOL_ARGS_CHARS + 500)
        val raw = """
            {"type":"tool","callID":"c1","tool":"edit",
             "state":{"status":"completed","input":{"content":"$huge"}}}
        """.trimIndent()
        val part = part(raw) as MessagePart.ToolCallPart
        assertEquals(PayloadLimits.MAX_TOOL_ARGS_CHARS, part.args.length)
        assertTrue(part.argsTruncated)
    }

    @Test
    fun `small tool args pass through unflagged`() {
        val raw = """
            {"type":"tool","callID":"c1","tool":"edit",
             "state":{"status":"completed","input":{"path":"a.kt"}}}
        """.trimIndent()
        val part = part(raw) as MessagePart.ToolCallPart
        assertEquals("""{"path":"a.kt"}""", part.args)
        assertFalse(part.argsTruncated)
    }

    @Test
    fun `oversized shell output is capped and flagged`() {
        val huge = "z".repeat(PayloadLimits.MAX_OUTPUT_CHARS + 10)
        val raw = """{"type":"shell","command":"ls","output":"$huge","exit":0}"""
        val part = part(raw) as MessagePart.ShellPart
        assertEquals(PayloadLimits.MAX_OUTPUT_CHARS, part.output.length)
        assertTrue(part.outputTruncated)
        assertEquals(0, part.exitCode)
    }

    @Test
    fun `oversized bash-tool state output is capped and flagged`() {
        val huge = "q".repeat(PayloadLimits.MAX_OUTPUT_CHARS + 10)
        val raw = """
            {"type":"tool","tool":"bash","callID":"c2",
             "state":{"status":"completed","input":{"command":"ls -la"},"output":"$huge"}}
        """.trimIndent()
        val part = part(raw) as MessagePart.ShellPart
        assertEquals(PayloadLimits.MAX_OUTPUT_CHARS, part.output.length)
        assertTrue(part.outputTruncated)
        assertEquals("ls -la", part.command)
    }

    @Test
    fun `small shell output stays unflagged`() {
        val raw = """{"type":"shell","command":"echo","output":"hi","exit":0}"""
        val part = part(raw) as MessagePart.ShellPart
        assertEquals("hi", part.output)
        assertFalse(part.outputTruncated)
    }

    // ---- Sprint M.5: text / reasoning history caps -------------------------
    @Test
    fun `small assistant text passes through unflagged`() {
        val raw = """{"type":"text","id":"p1","text":"short answer"}"""
        val part = part(raw) as MessagePart.TextPart
        assertEquals("short answer", part.content)
        assertFalse(part.contentTruncated)
    }

    @Test
    fun `assistant text exactly at the cap is accepted unflagged`() {
        val exact = "t".repeat(PayloadLimits.MAX_PART_CHARS)
        val raw = """{"type":"text","id":"p1","text":"$exact"}"""

        val part = part(raw) as MessagePart.TextPart

        assertEquals(PayloadLimits.MAX_PART_CHARS, part.content.length)
        assertFalse(part.contentTruncated)
    }

    @Test
    fun `oversized assistant text is capped before retention and flagged`() {
        val huge = "t".repeat(PayloadLimits.MAX_PART_CHARS + 1000)
        val raw = """{"type":"text","id":"p1","text":"$huge"}"""

        val part = part(raw) as MessagePart.TextPart

        assertEquals(PayloadLimits.MAX_PART_CHARS, part.content.length)
        assertTrue(part.contentTruncated)
        assertEquals("p1", part.id)
    }

    @Test
    fun `oversized reasoning is capped and flagged the same way`() {
        val huge = "r".repeat(PayloadLimits.MAX_PART_CHARS + 1)
        val raw = """{"type":"reasoning","id":"p2","thinking":"$huge"}"""

        val part = part(raw) as MessagePart.ReasoningPart

        assertEquals(PayloadLimits.MAX_PART_CHARS, part.thinking.length)
        assertTrue(part.thinkingTruncated)
        assertTrue("history parts stay finished", part.isFinished)
    }

    @Test
    fun `reasoning falling back to the text field is capped too`() {
        val huge = "f".repeat(PayloadLimits.MAX_PART_CHARS + 1)
        val raw = """{"type":"reasoning","id":"p3","text":"$huge"}"""

        val part = part(raw) as MessagePart.ReasoningPart

        assertEquals(PayloadLimits.MAX_PART_CHARS, part.thinking.length)
        assertTrue(part.thinkingTruncated)
    }
}

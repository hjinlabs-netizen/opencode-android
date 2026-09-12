package com.anomalyco.opencode.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Boundary behaviour of the approved limits table and surrogate-safe capping. */
class PayloadLimitsTest {

    @Test
    fun `approved limits table`() {
        assertEquals(64 * 1024, PayloadLimits.MAX_TOOL_ARGS_CHARS)
        assertEquals(128 * 1024, PayloadLimits.MAX_OUTPUT_CHARS)
        assertEquals(512 * 1024, PayloadLimits.MAX_PREVIEW_CHARS)
        assertEquals(500, PayloadLimits.MAX_TRANSCRIPT_MESSAGES)
        assertEquals(1_000, PayloadLimits.MAX_CACHED_SESSIONS)
        assertEquals(8, PayloadLimits.MAX_TRACKED_SERVERS)
    }

    @Test
    fun `text at or below the limit passes through untouched`() {
        val exact = "x".repeat(8)
        val exactResult = PayloadLimits.cap(exact, 8)
        assertSame(exact, exactResult.text)
        assertFalse(exactResult.truncated)

        val below = PayloadLimits.cap("abc", 8)
        assertEquals("abc", below.text)
        assertFalse(below.truncated)
    }

    @Test
    fun `over-limit text is cut exactly at the limit and flagged`() {
        val result = PayloadLimits.cap("x".repeat(9), 8)
        assertEquals(8, result.text.length)
        assertTrue(result.truncated)
    }

    @Test
    fun `cutting inside a surrogate pair backs off and never leaves a dangling high surrogate`() {
        // '😀' is a surrogate pair occupying code units 4 and 5.
        val text = "abcd" + "\uD83D\uDE00" + "efgh"
        val result = PayloadLimits.cap(text, 5) // would split the pair
        assertEquals(4, result.text.length)
        assertTrue(result.truncated)
        assertFalse(result.text.last().isHighSurrogate())
        assertEquals("abcd", result.text)
    }

    @Test
    fun `ending exactly after a full surrogate pair needs no back-off`() {
        val text = "ab" + "\uD83D\uDE00" + "cd" // pair sits at units 2..3
        val result = PayloadLimits.cap(text, 4) // cut right after the pair
        assertEquals(4, result.text.length)
        assertTrue(result.truncated)
        assertTrue(result.text[2].isHighSurrogate())
        assertTrue(result.text[3].isLowSurrogate())
    }

    @Test
    fun `named caps use the matching constants`() {
        assertEquals(
            PayloadLimits.MAX_TOOL_ARGS_CHARS,
            PayloadLimits.toolArgs("z".repeat(PayloadLimits.MAX_TOOL_ARGS_CHARS + 1)).text.length,
        )
        assertEquals(
            PayloadLimits.MAX_OUTPUT_CHARS,
            PayloadLimits.output("z".repeat(PayloadLimits.MAX_OUTPUT_CHARS + 1)).text.length,
        )
        assertEquals(
            PayloadLimits.MAX_PREVIEW_CHARS,
            PayloadLimits.preview("z".repeat(PayloadLimits.MAX_PREVIEW_CHARS + 1)).text.length,
        )
    }
}

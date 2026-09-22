package com.anomalyco.opencode.ui.chat

import com.anomalyco.opencode.data.PayloadLimits
import com.anomalyco.opencode.domain.model.DiffStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing gate behind P1-4's color-coded permission diff: [parsePermissionDiff]
 * must turn a valid unified patch into a structured [com.anomalyco.opencode.domain.model.FileDiff],
 * cap oversized patches BEFORE parsing (flagging truncation), and hand back
 * null - signaling the plain-text fallback - for blank / non-diff / hunk-less
 * input so no previously-renderable payload can vanish.
 */
class PermissionDiffParsingTest {

    @Test
    fun `null and blank diffs fall back`() {
        assertNull(parsePermissionDiff(null))
        assertNull(parsePermissionDiff(""))
        assertNull(parsePermissionDiff("   \n  \t "))
    }

    @Test
    fun `a valid single-file unified diff parses into structured hunks`() {
        val patch = """
            --- a/app.txt
            +++ b/app.txt
            @@ -1,2 +1,2 @@
             context
            -old line
            +new line
        """.trimIndent()

        val diff = parsePermissionDiff(patch)

        assertNotNull(diff)
        diff!!
        assertEquals("app.txt", diff.path)
        assertEquals(DiffStatus.MODIFIED, diff.status)
        assertEquals(1, diff.hunks.size)
        assertEquals(1, diff.additions)
        assertEquals(1, diff.deletions)
        assertFalse("a patch under the limit must not be flagged truncated", diff.truncated)
    }

    @Test
    fun `prose that is not a unified diff falls back`() {
        assertNull(parsePermissionDiff("hello world\nthis is not a diff at all"))
    }

    @Test
    fun `headers with no hunks fall back`() {
        val headerOnly = """
            --- a/f.kt
            +++ b/f.kt
        """.trimIndent()
        assertNull(parsePermissionDiff(headerOnly))
    }

    @Test
    fun `an oversized patch is capped before parsing and flagged truncated`() {
        val body = buildString {
            repeat(60_000) { append("+line ").append(it).append(" padding padding\n") }
        }
        val patch = "--- a/big.kt\n+++ b/big.kt\n@@ -0,0 +1,60000 @@\n" + body
        assertTrue(patch.length > PayloadLimits.MAX_PATCH_CHARS)

        val diff = parsePermissionDiff(patch)

        assertNotNull(diff)
        diff!!
        assertTrue("truncation must be flagged", diff.truncated)
        assertTrue("the leading part must still render as hunks", diff.hunks.isNotEmpty())
        assertTrue(diff.additions > 0)
    }
}

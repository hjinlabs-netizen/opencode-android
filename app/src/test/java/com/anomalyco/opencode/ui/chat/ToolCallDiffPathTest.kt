package com.anomalyco.opencode.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Per-file diff deep links (P1-1): the tool card reads a file path out of the
 * raw JSON arguments so it can navigate straight to that file's diff — but only
 * when a known key carries a real path, never a guess.
 */
class ToolCallDiffPathTest {

    @Test
    fun `reads the path key`() {
        assertEquals("src/App.kt", extractDiffPath("""{"path":"src/App.kt"}"""))
    }

    @Test
    fun `supports filePath, file and target keys`() {
        assertEquals("a.kt", extractDiffPath("""{"filePath":"a.kt"}"""))
        assertEquals("b.kt", extractDiffPath("""{"file":"b.kt"}"""))
        assertEquals("c.kt", extractDiffPath("""{"target":"c.kt"}"""))
    }

    @Test
    fun `prefers path over the lower-priority keys`() {
        assertEquals("win.kt", extractDiffPath("""{"file":"lose.kt","path":"win.kt"}"""))
    }

    @Test
    fun `ignores unknown keys`() {
        assertNull(extractDiffPath("""{"command":"ls -la","cwd":"/tmp"}"""))
    }

    @Test
    fun `returns null for blank or empty-object arguments`() {
        assertNull(extractDiffPath(""))
        assertNull(extractDiffPath("   "))
        assertNull(extractDiffPath("{}"))
    }

    @Test
    fun `returns null for a non-string or blank path value`() {
        assertNull(extractDiffPath("""{"path":42}"""))
        assertNull(extractDiffPath("""{"path":""}"""))
    }

    @Test
    fun `returns null for unparseable or truncated JSON`() {
        assertNull(extractDiffPath("""{"path":"a.kt"""))
        assertNull(extractDiffPath("not json at all"))
    }
}

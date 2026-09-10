package com.anomalyco.opencode.data.remote

import com.anomalyco.opencode.domain.model.DiffLineKind
import com.anomalyco.opencode.domain.model.DiffStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parsing rules for git-style unified patches of every shape. */
class UnifiedDiffParserTest {

    private val modification = """
        diff --git a/app.txt b/app.txt
        index 123..456 100644
        --- a/app.txt
        +++ b/app.txt
        @@ -1,4 +1,4 @@
         line1
        -line2
        +LINE TWO
        +extra
         line3
    """.trimIndent()

    @Test
    fun `parses a modified file with per-line numbers and counters`() {
        val diff = UnifiedDiffParser.parseSingle(modification)!!

        assertEquals("app.txt", diff.oldPath)
        assertEquals("app.txt", diff.path)
        assertEquals(DiffStatus.MODIFIED, diff.status)
        assertEquals(2, diff.additions)
        assertEquals(1, diff.deletions)

        val hunk = diff.hunks.single()
        assertEquals(1, hunk.oldStart)
        assertEquals(1, hunk.newStart)
        assertEquals(
            listOf(
                com.anomalyco.opencode.domain.model.DiffLine(DiffLineKind.CONTEXT, "line1", 1, 1),
                com.anomalyco.opencode.domain.model.DiffLine(DiffLineKind.DELETED, "line2", 2, null),
                com.anomalyco.opencode.domain.model.DiffLine(DiffLineKind.ADDED, "LINE TWO", null, 2),
                com.anomalyco.opencode.domain.model.DiffLine(DiffLineKind.ADDED, "extra", null, 3),
                com.anomalyco.opencode.domain.model.DiffLine(DiffLineKind.CONTEXT, "line3", 3, 4),
            ),
            hunk.lines,
        )
    }

    @Test
    fun `parses multi-file patches into one diff per file`() {
        val patch = """
            diff --git a/one.kt b/one.kt
            --- a/one.kt
            +++ b/one.kt
            @@ -1 +1 @@
            -a
            +b
            diff --git a/two.kt b/two.kt
            --- a/two.kt
            +++ b/two.kt
            @@ -1 +1 @@
            -c
            +d
        """.trimIndent()
        val diffs = UnifiedDiffParser.parse(patch)
        assertEquals(listOf("one.kt", "two.kt"), diffs.map { it.path })
    }

    @Test
    fun `dev null markers on either side map to added and deleted statuses`() {
        val added = UnifiedDiffParser.parseSingle(
            """
            --- /dev/null
            +++ b/new.txt
            @@ -0,0 +1,2 @@
            +l1
            +l2
            """.trimIndent(),
        )!!
        assertEquals(DiffStatus.ADDED, added.status)
        assertEquals("", added.oldPath)
        assertEquals(2, added.additions)

        val deleted = UnifiedDiffParser.parseSingle(
            """
            --- a/gone.txt
            +++ /dev/null
            @@ -1,1 +0,0 @@
            -x
            """.trimIndent(),
        )!!
        assertEquals(DiffStatus.DELETED, deleted.status)
        assertEquals("gone.txt", deleted.oldPath)
        assertEquals(1, deleted.deletions)
    }

    @Test
    fun `rename headers produce the RENAMED status`() {
        val diff = UnifiedDiffParser.parseSingle(
            """
            diff --git a/old.txt b/new.txt
            rename from old.txt
            rename to new.txt
            """.trimIndent(),
        )!!
        assertEquals(DiffStatus.RENAMED, diff.status)
        assertEquals("old.txt", diff.oldPath)
        assertEquals("new.txt", diff.newPath)
    }

    @Test
    fun `new file mode header maps to ADDED even with content hunks`() {
        val diff = UnifiedDiffParser.parseSingle(
            """
            diff --git a/f.txt b/f.txt
            new file mode 100644
            --- /dev/null
            +++ b/f.txt
            @@ -0,0 +1 @@
            +hello
            """.trimIndent(),
        )!!
        assertEquals(DiffStatus.ADDED, diff.status)
        assertEquals("hello", diff.hunks.single().lines.single().text)
    }

    @Test
    fun `unrecognised lines inside a hunk degrade to context`() {
        val diff = UnifiedDiffParser.parseSingle(
            """
            --- a/x
            +++ b/x
            @@ -1 +1 @@
            \ No newline at end of file
            """.trimIndent(),
        )!!
        assertEquals(DiffLineKind.CONTEXT, diff.hunks.single().lines.single().kind)
    }

    @Test
    fun `blank input yields no diffs`() {
        assertTrue(UnifiedDiffParser.parse("").isEmpty())
        assertTrue(UnifiedDiffParser.parse("   \n  ").isEmpty())
        assertNull(UnifiedDiffParser.parseSingle(""))
    }
}

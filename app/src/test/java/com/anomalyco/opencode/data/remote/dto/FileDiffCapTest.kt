package com.anomalyco.opencode.data.remote.dto

import com.anomalyco.opencode.data.PayloadLimits
import com.anomalyco.opencode.domain.model.DiffLineKind
import com.anomalyco.opencode.domain.model.FileDiff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sprint M.3: raw patch text is capped BEFORE the line-object-heavy parser.
 * Over-budget diffs degrade to the leading hunks + a typed `truncated` flag
 * (partial view beats a failed screen), while every parser input stays
 * bounded and metadata-only rows are untouched.
 */
class FileDiffCapTest {

    private fun patchSized(total: Int): String {
        val prefix = "--- a/f.kt\n+++ b/f.kt\n@@ -1,1 +1,1 @@\n+"
        val suffix = "\n"
        val text = "z".repeat((total - prefix.length - suffix.length).coerceAtLeast(1))
        return prefix + text + suffix
    }

    private fun multiFilePatch(files: Int, linesPerFile: Int): String = buildString {
        repeat(files) { f ->
            append("diff --git a/f$f.kt b/f$f.kt\n")
            append("--- a/f$f.kt\n+++ b/f$f.kt\n")
            append("@@ -1,${linesPerFile} +1,${linesPerFile} @@\n")
            repeat(linesPerFile) { l -> append("+content line $l for file $f with some filler\n") }
        }
    }

    private fun retainedTextLength(diff: FileDiff): Int =
        diff.hunks.sumOf { hunk -> hunk.lines.sumOf { it.text.length + hunk.header.length } }

    @Test
    fun `patch below the limit parses unchanged and stays unflagged`() {
        val small = multiFilePatch(files = 1, linesPerFile = 5)
        val diff = FileDiffDto(file = "f0.kt", patch = small).toDomain()

        assertFalse(diff.truncated)
        assertEquals(5, diff.additions)
        assertEquals(1, diff.hunks.size)
        assertEquals(5, diff.hunks.single().lines.count { it.kind == DiffLineKind.ADDED })
    }

    @Test
    fun `patch exactly at the limit is accepted unflagged`() {
        val exact = patchSized(PayloadLimits.MAX_PATCH_CHARS)
        assertEquals(PayloadLimits.MAX_PATCH_CHARS, exact.length)

        val diff = FileDiffDto(file = "f.kt", patch = exact).toDomain()

        assertFalse(diff.truncated)
        assertEquals(1, diff.hunks.size)
    }

    @Test
    fun `patch above the limit is cut before parsing and flagged`() {
        val huge = patchSized(PayloadLimits.MAX_PATCH_CHARS + 50_000)

        val diff = FileDiffDto(file = "f.kt", patch = huge).toDomain()

        assertTrue("truncation must be flagged", diff.truncated)
        // The parser only ever saw bounded input: retained text cannot
        // exceed the patch budget (plus per-line header copies stay small).
        assertTrue(retainedTextLength(diff) <= PayloadLimits.MAX_PATCH_CHARS)
        assertEquals(1, diff.additions) // the single (cut) line still renders
    }

    @Test
    fun `a cut landing mid-hunk never breaks the parser`() {
        // ~2 MB of small lines: the 1 MB boundary slices an active hunk.
        val big = multiFilePatch(files = 1, linesPerFile = 40_000)
        assertTrue(big.length > PayloadLimits.MAX_PATCH_CHARS)

        val diff = FileDiffDto(file = "f0.kt", patch = big).toDomain()

        assertTrue(diff.truncated)
        val kept = diff.hunks.first().lines.size
        assertTrue("leading part must survive: kept $kept", kept in 1 until 40_000)
        assertTrue(diff.additions in 1 until 40_000)
    }

    @Test
    fun `wrapper patches mark only the last file as truncated`() {
        // 3 files x ~490 KB -> ~1.4 MB total: the 1 MB cut lands inside the
        // third file, so all three rows parse and only the last is partial.
        val wrapper = DiffTextDto(diff = multiFilePatch(files = 3, linesPerFile = 10_000))
        assertTrue(wrapper.diff.length > PayloadLimits.MAX_PATCH_CHARS)

        val rows = wrapper.toDomain()

        assertEquals(3, rows.size)
        assertFalse(rows[0].truncated)
        assertFalse(rows[1].truncated)
        assertTrue(rows.last().truncated)
    }

    @Test
    fun `metadata-only rows without patch text stay untouched`() {
        val diff = FileDiffDto(file = "a.kt", status = "modified", additions = 3, deletions = 1).toDomain()

        assertFalse(diff.truncated)
        assertEquals(3, diff.additions)
        assertEquals(1, diff.deletions)
        assertTrue(diff.hunks.isEmpty())
    }
}

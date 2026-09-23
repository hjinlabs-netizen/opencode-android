package com.anomalyco.opencode.ui.files

import com.anomalyco.opencode.domain.model.DiffHunk
import com.anomalyco.opencode.domain.model.DiffLine
import com.anomalyco.opencode.domain.model.DiffLineKind
import com.anomalyco.opencode.domain.model.FileDiff
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Copy-patch affordance (P1 polish): the reconstructed patch must be a valid
 * git-style unified diff, re-prefixing each line from its kind (the parsed
 * line text stores no marker).
 */
class DiffPatchTextTest {

    @Test
    fun `reconstructs a header and prefixed hunk lines`() {
        val diff = FileDiff(
            oldPath = "a/old.kt",
            newPath = "a/new.kt",
            hunks = listOf(
                DiffHunk(
                    header = "@@ -1,3 +1,3 @@",
                    oldStart = 1,
                    newStart = 1,
                    lines = listOf(
                        DiffLine(DiffLineKind.CONTEXT, "keep"),
                        DiffLine(DiffLineKind.DELETED, "gone"),
                        DiffLine(DiffLineKind.ADDED, "fresh"),
                    ),
                ),
            ),
        )

        val expected = """
            |--- a/old.kt
            |+++ a/new.kt
            |@@ -1,3 +1,3 @@
            | keep
            |-gone
            |+fresh
            |""".trimMargin()

        assertEquals(expected, diff.toPatchText())
    }

    @Test
    fun `empty hunks yield only the file headers`() {
        val diff = FileDiff(oldPath = "x.kt", newPath = "x.kt")
        assertEquals("--- x.kt\n+++ x.kt\n", diff.toPatchText())
    }
}

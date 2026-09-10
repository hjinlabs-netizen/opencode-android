package com.anomalyco.opencode.data.remote

import com.anomalyco.opencode.domain.model.DiffHunk
import com.anomalyco.opencode.domain.model.DiffLine
import com.anomalyco.opencode.domain.model.DiffLineKind
import com.anomalyco.opencode.domain.model.DiffStatus
import com.anomalyco.opencode.domain.model.FileDiff

/**
 * Dependency-free parser for git-style unified diffs. Turns the raw patch
 * text the server returns into structured [FileDiff]s with per-line numbers
 * so the Compose viewer can color-code additions/deletions without
 * re-tokenising strings at render time.
 *
 * Handles multi-file patches, `diff --git`/`---`/`+++` headers, `/dev/null`
 * (add/delete) markers and `@@ -a,b +c,d @@` hunk ranges. Tolerant by design:
 * an unrecognised line inside a hunk becomes CONTEXT rather than throwing.
 */
object UnifiedDiffParser {

    private val HUNK_REGEX = Regex("""^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@""")

    fun parse(patch: String): List<FileDiff> {
        if (patch.isBlank()) return emptyList()
        val files = mutableListOf<Accumulator>()
        var current: Accumulator? = null

        for (raw in patch.lines()) {
            when {
                raw.startsWith("diff --git") -> {
                    current = Accumulator().also { files += it }
                    val path = raw.substringAfterLast(" b/", "")
                    if (path.isNotEmpty()) {
                        current.oldPath = path
                        current.newPath = path
                    }
                }
                raw.startsWith("--- ") -> {
                    if (current == null) {
                        current = Accumulator().also { files += it }
                    }
                    current.oldPath = stripPrefix(raw.substring(4))
                }
                raw.startsWith("+++ ") -> {
                    if (current == null) {
                        current = Accumulator().also { files += it }
                    }
                    current.newPath = stripPrefix(raw.substring(4))
                }
                raw.startsWith("new file") -> current?.newFile = true
                raw.startsWith("deleted file") -> current?.deletedFile = true
                raw.startsWith("rename to ") -> {
                    current?.renamed = true
                    current?.newPath = raw.substringAfter("rename to ").ifEmpty { current?.newPath }
                }
                raw.startsWith("rename from ") -> {
                    current?.renamed = true
                    current?.oldPath = raw.substringAfter("rename from ").ifEmpty { current?.oldPath }
                }
                HUNK_REGEX.matches(raw) -> {
                    val match = HUNK_REGEX.matchEntire(raw)!!
                    current = (current ?: Accumulator().also { files += it }).apply {
                        startHunk(
                            header = raw,
                            oldStart = match.groupValues[1].toInt(),
                            newStart = match.groupValues[2].toInt(),
                        )
                    }
                }
                current?.hunkLines != null -> current.appendLine(raw)
                // index/`index abc..def` and other metadata lines: ignore.
            }
        }
        return files.map { it.build() }
    }

    /** Convenience for single-file patches (empty/null when nothing parsed). */
    fun parseSingle(patch: String): FileDiff? = parse(patch).firstOrNull()

    private fun stripPrefix(value: String): String = when {
        value == "/dev/null" -> ""
        value.startsWith("a/") || value.startsWith("b/") -> value.substring(2)
        else -> value
    }

    private class Accumulator {
        var oldPath: String? = null
        var newPath: String? = null
        var newFile = false
        var deletedFile = false
        var renamed = false

        private val hunks = mutableListOf<Hunk>()
        var hunkLines: MutableList<DiffLine>? = null
            private set
        private var oldNumber = 0
        private var newNumber = 0

        fun startHunk(header: String, oldStart: Int, newStart: Int) {
            val lines = mutableListOf<DiffLine>()
            hunks += Hunk(
                DiffHunk(
                    header = header,
                    oldStart = oldStart,
                    newStart = newStart,
                    lines = lines,
                ),
                lines,
            )
            hunkLines = lines
            oldNumber = oldStart
            newNumber = newStart
        }

        fun appendLine(raw: String) {
            val lines = hunkLines ?: return
            when {
                raw.startsWith("+") -> {
                    lines += DiffLine(DiffLineKind.ADDED, raw.substring(1), null, newNumber++)
                }
                raw.startsWith("-") -> {
                    lines += DiffLine(DiffLineKind.DELETED, raw.substring(1), oldNumber++, null)
                }
                else -> {
                    val text = if (raw.startsWith(" ")) raw.substring(1) else raw
                    lines += DiffLine(DiffLineKind.CONTEXT, text, oldNumber++, newNumber++)
                }
            }
        }

        fun build(): FileDiff {
            var additions = 0
            var deletions = 0
            hunks.forEach { h ->
                h.lines.forEach { line ->
                    when (line.kind) {
                        DiffLineKind.ADDED -> additions++
                        DiffLineKind.DELETED -> deletions++
                        DiffLineKind.CONTEXT -> Unit
                    }
                }
            }
            val status = when {
                newFile -> DiffStatus.ADDED
                deletedFile -> DiffStatus.DELETED
                renamed -> DiffStatus.RENAMED
                // /dev/null on one side is the other canonical add/delete form.
                oldPath.isNullOrEmpty() && !newPath.isNullOrEmpty() -> DiffStatus.ADDED
                newPath.isNullOrEmpty() && !oldPath.isNullOrEmpty() -> DiffStatus.DELETED
                else -> DiffStatus.MODIFIED
            }
            return FileDiff(
                oldPath = oldPath.orEmpty(),
                newPath = newPath.orEmpty().ifEmpty { oldPath.orEmpty() },
                status = status,
                hunks = hunks.map { it.snapshot },
                additions = additions,
                deletions = deletions,
            )
        }

        private class Hunk(val snapshot: DiffHunk, val lines: MutableList<DiffLine>)
    }
}

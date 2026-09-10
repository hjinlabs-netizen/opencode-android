package com.anomalyco.opencode.domain.model

/**
 * A structured unified diff for one file, produced by parsing a git-style
 * patch (`GET /fs/diff`). Line-level metadata drives the color-coded viewer.
 */
data class FileDiff(
    val oldPath: String,
    val newPath: String,
    val status: DiffStatus = DiffStatus.MODIFIED,
    val hunks: List<DiffHunk> = emptyList(),
    val additions: Int = 0,
    val deletions: Int = 0,
) {
    val path: String get() = newPath.ifBlank { oldPath }
}

data class DiffHunk(
    val header: String,
    val oldStart: Int,
    val newStart: Int,
    val lines: List<DiffLine>,
)

/** One line in a hunk. [kind] selects the rendering color. */
data class DiffLine(
    val kind: DiffLineKind,
    val text: String,
    val oldNumber: Int? = null,
    val newNumber: Int? = null,
)

enum class DiffLineKind { CONTEXT, ADDED, DELETED }

enum class DiffStatus { ADDED, MODIFIED, DELETED, RENAMED, UNCHANGED }

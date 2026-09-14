package com.anomalyco.opencode.domain.model

/**
 * Pure, Android-free handling of the RELATIVE server paths the file
 * endpoints speak (`GET /file?path=Desktop\Android_CLI`). Foundation for the
 * future folder picker (W.2+); shaped by the W.0 spike verdicts:
 *
 *  - browsing is rooted at the server's project directory, spelled `"."`;
 *  - absolute paths and `..` traversal are REJECTED by the server (HTTP 500),
 *    so this utility never produces either;
 *  - `\` and `/` are both accepted separators (Windows server verified);
 *    existing separator characters are PRESERVED rather than rewritten,
 *    because POSIX servers treat `\` as a literal filename character.
 *
 * Canonical guarantees: no duplicate separators, no trailing separator,
 * no empty or `.`/`..` segments, `"."` for the root.
 */
object ServerPath {

    /** The server's project root spelling. */
    const val ROOT = "."

    /** Separator used when joining paths that carry none of their own. */
    const val DEFAULT_SEPARATOR = '\\'

    /**
     * Resolves the relative path: collapses duplicate separators, consumes
     * `.` segments and resolves `..` against the preceding segment (popping
     * at the top level just stops there), drops leading/trailing separators
     * (absolute input is treated as relative) and maps blank or fully
     * escaped input to [ROOT]. The first separator character found in the
     * raw input becomes the join style for the result. The output never
     * contains a `..` segment.
     */
    fun normalize(path: String): String {
        val resolved = mutableListOf<String>()
        path.trim().split('\\', '/').forEach { segment ->
            when {
                segment.isEmpty() || segment == "." -> Unit
                segment == ".." -> if (resolved.isNotEmpty()) resolved.removeAt(resolved.lastIndex)
                else -> resolved += segment
            }
        }
        return if (resolved.isEmpty()) ROOT else resolved.joinToString(separatorOf(path))
    }

    /**
     * The breadcrumb level above [path]: `Desktop\Folder` -> `Desktop`,
     * `Desktop` -> [ROOT], [ROOT] -> `null` (no parent). Never emits `..`.
     */
    fun parentOf(path: String): String? {
        val normalized = normalize(path)
        if (normalized == ROOT) return null
        val index = normalized.lastIndexOfAnySeparator()
        return if (index < 0) ROOT else normalized.substring(0, index).ifEmpty { ROOT }
    }

    /**
     * Appends a relative [child] to a relative [root] without duplicating
     * separators. [ROOT] or blank on either side yields the other side.
     * The separator is the one already present in [root] (after
     * normalization), else [DEFAULT_SEPARATOR]. Never produces an absolute
     * path or a `..` segment.
     */
    fun join(root: String, child: String): String {
        val normalizedRoot = normalize(root)
        val normalizedChild = normalize(child)
        return when {
            normalizedRoot == ROOT -> normalizedChild
            normalizedChild == ROOT -> normalizedRoot
            else -> normalizedRoot + (normalizedRoot.firstSeparatorOrNull() ?: DEFAULT_SEPARATOR) + normalizedChild
        }
    }

    /** True only for the project root representation (`""`, `"."`, blanks). */
    fun isRoot(path: String): Boolean = normalize(path) == ROOT

    private fun String.lastIndexOfAnySeparator(): Int =
        indexOfLast { it == '\\' || it == '/' }

    private fun separatorOf(raw: String): String = (raw.firstSeparatorOrNull() ?: DEFAULT_SEPARATOR).toString()

    private fun String.firstSeparatorOrNull(): Char? = firstOrNull { it == '\\' || it == '/' }
}

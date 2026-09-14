package com.anomalyco.opencode.domain.model

/**
 * A node in the project file tree returned by the `/fs` endpoints.
 *
 * [children] is only populated when the server returns a recursive listing;
 * lazy loading fetches children per [path] on expand.
 */
data class FileNode(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long = 0L,
    val children: List<FileNode> = emptyList(),
) {
    val extension: String
        get() = if (isDirectory) "" else name.substringAfterLast('.', "")
}

/** Decoded contents of a single file (`GET /fs/read`). */
data class FileContent(
    val path: String,
    val content: String,
    val mimeType: String? = null,
    /** True when [content] was cut at the preview memory limit at decode. */
    val truncated: Boolean = false,
)

/**
 * One directory level plus the honest marker that the server returned MORE
 * entries than the client keeps (Sprint M.4 memory cap): the listing is
 * bounded to `PayloadLimits.MAX_LIST_NODES` BEFORE domain objects are
 * created, and the UI shows a localized "showing first N" notice.
 */
data class FileListing(
    val entries: List<FileNode> = emptyList(),
    val truncated: Boolean = false,
)

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
)

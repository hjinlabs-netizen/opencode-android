package com.anomalyco.opencode.data.remote.dto

import com.anomalyco.opencode.data.remote.UnifiedDiffParser
import com.anomalyco.opencode.domain.model.DiffStatus
import com.anomalyco.opencode.domain.model.FileContent
import com.anomalyco.opencode.domain.model.FileDiff
import com.anomalyco.opencode.domain.model.FileNode
import kotlinx.serialization.Serializable

/**
 * Wire shape of a filesystem entry (`GET /fs/list`). The server has used
 * `type: "file"|"directory"`, plain booleans, and `entries`/`children` for
 * nested listings across builds; every variant is accepted here.
 */
@Serializable
data class FileNodeDto(
    val path: String? = null,
    val name: String = "",
    val type: String? = null,
    val isDirectory: Boolean? = null,
    val directory: Boolean? = null,
    val size: Long? = null,
    val children: List<FileNodeDto>? = null,
    val entries: List<FileNodeDto>? = null,
) {
    private val isDir: Boolean
        get() = isDirectory ?: directory ?: type?.equals("directory", ignoreCase = true) ?: false

    /** [parentPath] qualifies the child path only when the server omits it. */
    fun toDomain(parentPath: String = ""): FileNode {
        val full = path?.ifBlank { null } ?: parentPath.plusScoped(name)
        return FileNode(
            path = full,
            name = name.ifBlank { full.substringAfterLast('/') },
            isDirectory = isDir,
            size = size ?: 0L,
            children = (children ?: entries).orEmpty().map { it.toDomain(full) },
        )
    }

    private fun String.plusScoped(child: String): String =
        if (isBlank()) child else "$this/$child"
}

/** Wire shape of `GET /fs/read`. */
@Serializable
data class FileContentDto(
    val path: String = "",
    val content: String = "",
    val type: String? = null,
    val mime: String? = null,
    val mimeType: String? = null,
) {
    fun toDomain(requestedPath: String = path): FileContent = FileContent(
        path = requestedPath.ifBlank { path },
        content = content,
        mimeType = mime ?: mimeType ?: type,
    )
}

/**
 * One changed file in a diff listing (`GET /fs/diff` / `/vcs/diff`): carries
 * either a raw unified patch (parsed downstream) or pre-split metadata.
 */
@Serializable
data class FileDiffDto(
    val file: String? = null,
    val path: String? = null,
    val name: String? = null,
    val status: String? = null,
    val additions: Int = 0,
    val deletions: Int = 0,
    val patch: String? = null,
    val diff: String? = null,
) {
    val targetPath: String get() = file ?: path ?: name ?: ""

    /** Parses the embedded patch text (may be blank for metadata-only rows). */
    fun toDomain(): FileDiff {
        val patchText = patch ?: diff
        val parsed = patchText?.let { UnifiedDiffParser.parseSingle(it) }
        val path = targetPath.ifBlank { parsed?.path.orEmpty() }
        return FileDiff(
            oldPath = parsed?.oldPath?.ifBlank { path } ?: path,
            newPath = parsed?.newPath?.ifBlank { path } ?: path,
            status = parsed?.status ?: statusFromWire(status),
            hunks = parsed?.hunks.orEmpty(),
            additions = if (additions > 0) additions else parsed?.additions ?: 0,
            deletions = if (deletions > 0) deletions else parsed?.deletions ?: 0,
        )
    }

    companion object {
        fun statusFromWire(value: String?): DiffStatus = when (value?.lowercase()) {
            "added", "add", "new", "created" -> DiffStatus.ADDED
            "deleted", "delete", "removed" -> DiffStatus.DELETED
            "renamed", "rename" -> DiffStatus.RENAMED
            "unchanged", "equal" -> DiffStatus.UNCHANGED
            else -> DiffStatus.MODIFIED
        }
    }
}

/** Wrapper `GET /fs/diff` may return: `{ "diff": "<patch text>" }`. */
@Serializable
data class DiffTextDto(
    val diff: String = "",
    val patch: String = "",
    val files: List<FileDiffDto> = emptyList(),
) {
    fun toDomain(): List<FileDiff> =
        if (files.isNotEmpty()) files.map { it.toDomain() }
        else UnifiedDiffParser.parse(patch.ifBlank { diff })
}

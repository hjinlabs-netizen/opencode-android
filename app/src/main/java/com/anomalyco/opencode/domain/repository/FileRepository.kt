package com.anomalyco.opencode.domain.repository

import com.anomalyco.opencode.domain.model.FileContent
import com.anomalyco.opencode.domain.model.FileDiff
import com.anomalyco.opencode.domain.model.FileNode

/**
 * Read-only access to the OpenCode server's project filesystem view.
 * All paths are server-side, project-relative ("" means the project root).
 */
interface FileRepository {

    /** One level of a directory listing (`GET /fs/list`). */
    suspend fun listDirectory(path: String = ""): Result<List<FileNode>>

    /** UTF-8 content of one file (`GET /fs/read`). */
    suspend fun readFile(path: String): Result<FileContent>

    /** Unified diff of one working-tree file, parsed (`GET /fs/diff`). */
    suspend fun diffFile(path: String): Result<FileDiff>

    /** All working-tree changes vs HEAD (files + parsed patches). */
    suspend fun workingTreeDiff(): Result<List<FileDiff>>
}

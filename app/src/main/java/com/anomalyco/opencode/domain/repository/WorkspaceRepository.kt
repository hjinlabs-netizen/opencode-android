package com.anomalyco.opencode.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * Client-side workspace bookkeeping (Phase 5). Currently just the most
 * recently used session working directories offered when creating a session.
 */
interface WorkspaceRepository {

    /** Recent directories, most-recent first, deduplicated, capped. */
    val recentDirectories: Flow<List<String>>

    /** Record [directory] as most recently used; blanks are ignored. */
    suspend fun rememberDirectory(directory: String)
}

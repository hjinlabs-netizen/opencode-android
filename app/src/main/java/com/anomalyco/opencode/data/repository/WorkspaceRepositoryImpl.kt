package com.anomalyco.opencode.data.repository

import com.anomalyco.opencode.data.settings.PreferenceStore
import com.anomalyco.opencode.domain.repository.WorkspaceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Concrete [WorkspaceRepository]: recent directories persisted as a JSON
 * string array in the plain [PreferenceStore] (same layer as theme mode —
 * these paths are not secrets). A DataStore migration is tracked in
 * NEW_ROADMAP.md; the [PreferenceStore] seam keeps this testable meanwhile.
 */
@Singleton
class WorkspaceRepositoryImpl @Inject constructor(
    private val store: PreferenceStore,
    private val json: Json,
) : WorkspaceRepository {

    private val serializer = ListSerializer(String.serializer())

    private val _recentDirectories = MutableStateFlow(read())
    override val recentDirectories: Flow<List<String>> = _recentDirectories.asStateFlow()

    override suspend fun rememberDirectory(directory: String) {
        val trimmed = directory.trim()
        if (trimmed.isEmpty()) return
        val updated = (listOf(trimmed) + read()).distinct().take(MAX_RECENT)
        store.putString(KEY_RECENT_DIRS, json.encodeToString(serializer, updated))
        _recentDirectories.value = updated
    }

    private fun read(): List<String> {
        val raw = store.getString(KEY_RECENT_DIRS) ?: return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }

    private companion object {
        const val KEY_RECENT_DIRS = "recent_directories"
        const val MAX_RECENT = 8
    }
}

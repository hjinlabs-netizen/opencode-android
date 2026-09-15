package com.anomalyco.opencode.data.repository

import com.anomalyco.opencode.data.remote.DebugLog
import com.anomalyco.opencode.data.remote.OpenCodeApi
import com.anomalyco.opencode.data.remote.dto.ConfigPatchDto
import com.anomalyco.opencode.data.remote.requireActiveServer
import com.anomalyco.opencode.data.settings.PreferenceStore
import com.anomalyco.opencode.domain.model.ModelSelection
import com.anomalyco.opencode.domain.model.ProviderConfig
import com.anomalyco.opencode.domain.repository.ConnectionRepository
import com.anomalyco.opencode.domain.repository.ModelRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Concrete [ModelRepository]. The "current model" flag is stitched from two
 * sources (`/config` for the active selection, `/provider` for the catalog);
 * a config read failure must never hide the catalog, so it degrades to
 * unflagged providers instead of an error.
 *
 * Selections are additionally mirrored into [PreferenceStore] (plain prefs —
 * a model id is not a secret) so the last user choice survives process
 * restarts even when the server reports no active model (`preferredSelection`).
 */
@Singleton
class ModelRepositoryImpl @Inject constructor(
    private val api: OpenCodeApi,
    private val connectionRepository: ConnectionRepository,
    private val store: PreferenceStore,
) : ModelRepository {

    override suspend fun fetchProviders(): Result<List<ProviderConfig>> =
        guarded {
            val server = connectionRepository.requireActiveServer()
            val current = runCatching {
                api.getConfig(server.baseUrl, server.token).toSelection()
            }.getOrNull()
            api.getProviders(server.baseUrl, server.token).toDomain(current)
        }.onSuccess { providers ->
            // Device-diagnosis breadcrumb (L.2 pattern): an empty or failing
            // catalog was invisible on device before the picker bug.
            DebugLog.log(
                "models: catalog ok providers=${providers.size} " +
                    "models=${providers.sumOf { it.models.size }}",
            )
        }.onFailure { error ->
            DebugLog.log("models: catalog load failed: ${error.message}")
        }

    override suspend fun setActiveModel(providerId: String, modelId: String): Result<Unit> {
        // Persist the intent first: even if the network switch fails, the next
        // cold start can retry applying it.
        rememberSelection(providerId, modelId)
        return guarded {
            val server = connectionRepository.requireActiveServer()
            api.updateConfig(
                server.baseUrl,
                server.token,
                ConfigPatchDto(model = "$providerId/$modelId"),
            )
        }
    }

    override fun preferredSelection(): ModelSelection? {
        val provider = store.getString(KEY_PROVIDER) ?: return null
        val model = store.getString(KEY_MODEL) ?: return null
        if (provider.isBlank() || model.isBlank()) return null
        return ModelSelection(provider, model)
    }

    private fun rememberSelection(providerId: String, modelId: String) {
        store.putString(KEY_PROVIDER, providerId)
        store.putString(KEY_MODEL, modelId)
    }

    private suspend fun <T> guarded(block: suspend () -> T): Result<T> = apiCall(block)

    private companion object {
        const val KEY_PROVIDER = "last_selected_provider_id"
        const val KEY_MODEL = "last_selected_model_id"
    }
}

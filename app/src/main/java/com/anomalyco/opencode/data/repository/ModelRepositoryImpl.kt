package com.anomalyco.opencode.data.repository

import com.anomalyco.opencode.data.remote.OpenCodeApi
import com.anomalyco.opencode.data.remote.dto.ConfigPatchDto
import com.anomalyco.opencode.data.remote.requireActiveServer
import com.anomalyco.opencode.data.remote.toFriendlyApiException
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
 */
@Singleton
class ModelRepositoryImpl @Inject constructor(
    private val api: OpenCodeApi,
    private val connectionRepository: ConnectionRepository,
) : ModelRepository {

    override suspend fun fetchProviders(): Result<List<ProviderConfig>> = guarded {
        val server = connectionRepository.requireActiveServer()
        val current = runCatching {
            api.getConfig(server.baseUrl, server.token).toSelection()
        }.getOrNull()
        api.getProviders(server.baseUrl, server.token).toDomain(current)
    }

    override suspend fun setActiveModel(providerId: String, modelId: String): Result<Unit> = guarded {
        val server = connectionRepository.requireActiveServer()
        api.updateConfig(
            server.baseUrl,
            server.token,
            ConfigPatchDto(model = "$providerId/$modelId"),
        )
    }

    private suspend fun <T> guarded(block: suspend () -> T): Result<T> =
        runCatching { block() }
            .recoverCatching { throw it.toFriendlyApiException() }
}

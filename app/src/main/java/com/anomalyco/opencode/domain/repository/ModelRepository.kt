package com.anomalyco.opencode.domain.repository

import com.anomalyco.opencode.domain.model.ProviderConfig

/**
 * LLM provider/model catalog and the active-model mutation
 * (`GET /provider` + `POST /config`).
 */
interface ModelRepository {

    /** Providers with their models; the server's current model is flagged. */
    suspend fun fetchProviders(): Result<List<ProviderConfig>>

    /** Make [providerId]/[modelId] the agent's active model. */
    suspend fun setActiveModel(providerId: String, modelId: String): Result<Unit>
}

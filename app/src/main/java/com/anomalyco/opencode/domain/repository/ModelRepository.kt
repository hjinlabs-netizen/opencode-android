package com.anomalyco.opencode.domain.repository

import com.anomalyco.opencode.domain.model.ModelSelection
import com.anomalyco.opencode.domain.model.ProviderConfig

/**
 * LLM provider/model catalog and the active-model mutation
 * (`GET /provider` + `POST /config`), plus local persistence of the user's
 * last choice so it survives app restarts even when the server does not
 * report an active model.
 */
interface ModelRepository {

    /** Providers with their models; the server's current model is flagged. */
    suspend fun fetchProviders(): Result<List<ProviderConfig>>

    /**
     * Make [providerId]/[modelId] the agent's active model (via `/config`) and
     * persist it locally for cold-start restore.
     */
    suspend fun setActiveModel(providerId: String, modelId: String): Result<Unit>

    /** The last locally-selected model, or null if none was ever chosen. */
    fun preferredSelection(): ModelSelection?
}

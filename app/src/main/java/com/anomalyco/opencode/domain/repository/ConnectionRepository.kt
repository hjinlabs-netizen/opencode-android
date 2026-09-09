package com.anomalyco.opencode.domain.repository

import com.anomalyco.opencode.domain.model.ServerConfig
import com.anomalyco.opencode.domain.model.HealthInfo
import kotlinx.coroutines.flow.Flow

/**
 * Single source of truth for the persisted server configuration and
 * for live server health checks.
 */
interface ConnectionRepository {

    /** Emits the saved [ServerConfig], if any. Completes without emitting when empty. */
    val config: Flow<ServerConfig?>

    /** Persist the configuration securely (EncryptedSharedPreferences). */
    suspend fun saveConfig(config: ServerConfig)

    /** Clear the persisted configuration. */
    suspend fun clearConfig()

    /**
     * Run a health check against the given server.
     *
     * @return [Result.success] with [HealthInfo] when the server responds.
     * @return [Result.failure] with a descriptive exception otherwise.
     */
    suspend fun checkHealth(config: ServerConfig): Result<HealthInfo>
}

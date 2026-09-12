package com.anomalyco.opencode.data.repository

import com.anomalyco.opencode.data.remote.OpenCodeApi
import com.anomalyco.opencode.data.settings.SecureSettingsStore
import com.anomalyco.opencode.domain.model.HealthInfo
import com.anomalyco.opencode.domain.model.ServerConfig
import com.anomalyco.opencode.domain.repository.ConnectionRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Concrete [ConnectionRepository]. Bridges the encrypted settings store and
 * the Ktor-backed API; failures surface as typed errors via the shared
 * [apiCall] wrapper.
 */
@Singleton
class ConnectionRepositoryImpl @Inject constructor(
    private val settings: SecureSettingsStore,
    private val api: OpenCodeApi,
) : ConnectionRepository {

    override val config: Flow<ServerConfig?> = settings.config

    override suspend fun saveConfig(config: ServerConfig) = settings.save(config)

    override suspend fun clearConfig() = settings.clear()

    override suspend fun checkHealth(config: ServerConfig): Result<HealthInfo> =
        apiCall { api.health(config.normalizedUrl, config.token) }
}

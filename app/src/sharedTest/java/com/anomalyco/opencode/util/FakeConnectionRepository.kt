package com.anomalyco.opencode.util

import com.anomalyco.opencode.domain.model.HealthInfo
import com.anomalyco.opencode.domain.model.ServerConfig
import com.anomalyco.opencode.domain.repository.ConnectionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * [ConnectionRepository] double serving a configurable (default: one LAN
 * server) config, with a mutable flow so tests simulate server changes.
 *
 * Lives in `src/sharedTest` (not `TestHttp.kt`) because the Android device
 * tier reuses it WITHOUT the MockEngine stack: the shared SSE fixture must
 * compile into `androidTest` with zero ktor-client-mock dependencies.
 */
class FakeConnectionRepository(
    server: ServerConfig? = ServerConfig("http://srv:4096/", "tok"),
) : ConnectionRepository {
    /** Public so tests can simulate server config changes. */
    val configFlow = MutableStateFlow(server)
    override val config: Flow<ServerConfig?> = configFlow
    override suspend fun saveConfig(config: ServerConfig) = Unit
    override suspend fun clearConfig() = Unit
    override suspend fun checkHealth(config: ServerConfig): Result<HealthInfo> =
        Result.success(HealthInfo())
}

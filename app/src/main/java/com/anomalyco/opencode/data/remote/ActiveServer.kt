package com.anomalyco.opencode.data.remote

import com.anomalyco.opencode.domain.model.ServerConfig
import com.anomalyco.opencode.domain.repository.ConnectionRepository
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Resolves the persisted, normalized [ServerConfig] from the connection
 * store, giving the encrypted store's async initial load a short grace
 * period. Throws [NoServerConfiguredException] when truly unconfigured.
 */
internal suspend fun ConnectionRepository.requireActiveServer(): ServerConfig =
    withTimeoutOrNull(ACTIVE_SERVER_TIMEOUT_MS) { config.filterNotNull().first() }
        ?.let { it.copy(baseUrl = it.normalizedUrl) }
        ?: throw NoServerConfiguredException()

private const val ACTIVE_SERVER_TIMEOUT_MS = 3_000L

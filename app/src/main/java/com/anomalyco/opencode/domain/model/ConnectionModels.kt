package com.anomalyco.opencode.domain.model

import com.anomalyco.opencode.domain.error.OpenCodeError
import kotlinx.serialization.Serializable

/**
 * Snapshot of the remote OpenCode instance returned by a successful
 * health check (`GET /global/health`).
 */
@Serializable
data class HealthInfo(
    val version: String? = null,
    val commit: String? = null,
) {
    companion object {
        /** Health responses may be empty objects; default to "unknown". */
        fun empty() = HealthInfo(version = null, commit = null)
    }
}

/**
 * Closed-set of connection lifecycle states surfaced to the UI.
 * Rendered as a sealed interface so Compose can exhaustively `when` over it.
 */
sealed interface ConnectionState {
    /** No server configured or connection explicitly closed. */
    data object Disconnected : ConnectionState

    /** A connection attempt is in flight. */
    data object Connecting : ConnectionState

    /** Health check succeeded; carries the server info. */
    data class Connected(val info: HealthInfo) : ConnectionState

    /** Connection attempt failed; carries the typed error for localized rendering. */
    data class Error(val error: OpenCodeError) : ConnectionState
}

package com.anomalyco.opencode.data.connection

import com.anomalyco.opencode.domain.error.toDisplayError
import com.anomalyco.opencode.domain.model.ConnectionState
import com.anomalyco.opencode.domain.model.ServerConfig
import com.anomalyco.opencode.domain.repository.ConnectionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central connection state machine.
 *
 * Owns the app-wide [ConnectionState] so every screen can observe it.
 * Transitions:
 *   Disconnected/Connected/Error -> Connecting  (on [connect])
 *   Connecting -> Connected | Error              (on result)
 *   any -> Disconnected                           (on [disconnect])
 *
 * The persisted config from [ConnectionRepository] is the single source of
 * truth for "which server"; no parallel last-connected copy is kept (a
 * configuration is only ever persisted after a successful health check).
 */
@Singleton
class ConnectionStateManager @Inject constructor(
    private val repository: ConnectionRepository,
) {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    /**
     * Run a health check against [config] and reflect the outcome in [state].
     * Safe to call repeatedly (e.g. a "retry" button).
     */
    suspend fun connect(config: ServerConfig) {
        _state.value = ConnectionState.Connecting
        repository.checkHealth(config)
            .onSuccess { info -> _state.value = ConnectionState.Connected(info) }
            .onFailure { error -> _state.value = ConnectionState.Error(error.toDisplayError()) }
    }

    /** Reset to [ConnectionState.Disconnected] without touching saved config. */
    fun disconnect() {
        _state.value = ConnectionState.Disconnected
    }
}

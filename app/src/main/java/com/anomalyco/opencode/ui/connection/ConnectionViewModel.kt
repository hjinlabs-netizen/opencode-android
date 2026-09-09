package com.anomalyco.opencode.ui.connection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anomalyco.opencode.data.connection.ConnectionStateManager
import com.anomalyco.opencode.domain.model.ConnectionState
import com.anomalyco.opencode.domain.model.ServerConfig
import com.anomalyco.opencode.domain.repository.ConnectionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Editable form state, kept separate from the immutable [ServerConfig]. */
data class ConnectionUiState(
    val url: String = "",
    val token: String = "",
    val connection: ConnectionState = ConnectionState.Disconnected,
    /** True while a save operation is in flight. */
    val saving: Boolean = false,
    /** Set once the initial config load finished, so the form isn't cleared. */
    val loaded: Boolean = false,
)

/**
 * Drives the Dashboard/Connection screen: form input, health check and
 * secure persistence. Everything is exposed as immutable [StateFlow]s —
 * the composable only ever reads.
 */
@HiltViewModel
class ConnectionViewModel @Inject constructor(
    private val repository: ConnectionRepository,
    private val connectionManager: ConnectionStateManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConnectionUiState())
    val uiState: StateFlow<ConnectionUiState> = _uiState.asStateFlow()

    /** Whether the user persisted any config ever (used for save-button label). */
    val hasSavedConfig: StateFlow<Boolean> =
        repository.config
            .map { it != null }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        // Seed the form with the saved config, if any.
        viewModelScope.launch {
            repository.config.collect { saved ->
                if (saved != null) {
                    _uiState.update {
                        it.copy(
                            url = saved.baseUrl,
                            token = saved.token,
                            loaded = true,
                        )
                    }
                } else if (!_uiState.value.loaded) {
                    _uiState.update { it.copy(loaded = true) }
                }
            }
        }
        // Mirror the connection manager state into the UI state.
        viewModelScope.launch {
            connectionManager.state.collect { s ->
                _uiState.update { it.copy(connection = s) }
            }
        }
    }

    fun onUrlChange(value: String) = _uiState.update { it.copy(url = value) }

    fun onTokenChange(value: String) = _uiState.update { it.copy(token = value) }

    /** Validate input, run the health check, and persist on success. */
    fun testAndSave() {
        val state = _uiState.value
        val url = state.url.trim()
        if (url.isEmpty()) {
            _uiState.update { it.copy(connection = ConnectionState.Error("Sunucu adresi boş olamaz.")) }
            return
        }
        if (!(url.startsWith("http://") || url.startsWith("https://"))) {
            _uiState.update { it.copy(connection = ConnectionState.Error("Adres http:// veya https:// ile başlamalı.")) }
            return
        }

        val config = ServerConfig(baseUrl = url, token = state.token.trim())

        viewModelScope.launch {
            _uiState.update { it.copy(saving = true) }
            connectionManager.connect(config)
            // Persist only on success so a bad server never overwrites a working one.
            val connected = connectionManager.state.value is ConnectionState.Connected
            if (connected) {
                repository.saveConfig(config)
            }
            _uiState.update { it.copy(saving = false) }
        }
    }

    fun disconnect() = connectionManager.disconnect()
}

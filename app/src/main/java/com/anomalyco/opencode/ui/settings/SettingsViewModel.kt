package com.anomalyco.opencode.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anomalyco.opencode.BuildConfig
import com.anomalyco.opencode.data.connection.ConnectionStateManager
import com.anomalyco.opencode.domain.model.ConnectionState
import com.anomalyco.opencode.domain.model.ServerConfig
import com.anomalyco.opencode.domain.model.ThemeMode
import com.anomalyco.opencode.domain.repository.ConnectionRepository
import com.anomalyco.opencode.domain.repository.ModelRepository
import com.anomalyco.opencode.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Immutable render state for [SettingsScreen]. */
data class SettingsUiState(
    val serverUrl: String = "",
    val tokenMasked: String = "",
    val hasToken: Boolean = false,
    val connection: ConnectionState = ConnectionState.Disconnected,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val isCheckingHealth: Boolean = false,
    /** Round-trip latency of the last successful health probe. */
    val latencyMs: Long? = null,
    val serverVersion: String? = null,
    /** Active `provider/model` for diagnostics (null until loaded). */
    val activeModel: String? = null,
    val appVersion: String = BuildConfig.VERSION_NAME,
    val error: String? = null,
)

/**
 * Settings hub (Phase 4): server overview + disconnect/erase, theme mode,
 * and quick diagnostics (latency probe, server build, active model).
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val connectionRepository: ConnectionRepository,
    private val connectionManager: ConnectionStateManager,
    private val settingsRepository: SettingsRepository,
    private val modelRepository: ModelRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    /** Latest persisted config (keeps the raw token for health probes). */
    private var savedConfig: ServerConfig? = null

    init {
        viewModelScope.launch {
            connectionRepository.config.collect { saved ->
                savedConfig = saved
                _uiState.update {
                    it.copy(
                        serverUrl = saved?.normalizedUrl.orEmpty(),
                        hasToken = !saved?.token.isNullOrBlank(),
                        tokenMasked = maskToken(saved?.token.orEmpty()),
                    )
                }
            }
        }
        viewModelScope.launch {
            connectionManager.state.collect { s ->
                _uiState.update { it.copy(connection = s) }
            }
        }
        viewModelScope.launch {
            settingsRepository.themeMode.collect { mode ->
                _uiState.update { it.copy(themeMode = mode) }
            }
        }
        loadActiveModel()
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    }

    /**
     * Timed health probe. Goes through [ConnectionStateManager] so a
     * successful check also revives the app-wide connection (and the SSE
     * stream via `lastConnectedConfig`), not just this screen's info.
     */
    fun runHealthCheck() {
        val server = connectionManager.lastConnectedConfig ?: savedConfig ?: return
        viewModelScope.launch {
            _uiState.update {
                it.copy(isCheckingHealth = true, error = null, latencyMs = null)
            }
            val startedAt = System.nanoTime()
            connectionManager.connect(server)
            val latencyMs = (System.nanoTime() - startedAt) / 1_000_000
            val settled = connectionManager.state.value
            _uiState.update { current ->
                current.copy(
                    isCheckingHealth = false,
                    latencyMs = latencyMs.takeIf { settled is ConnectionState.Connected },
                    serverVersion = (settled as? ConnectionState.Connected)?.info?.version,
                    error = (settled as? ConnectionState.Error)?.message ?: current.error,
                )
            }
        }
    }

    /** End the live connection but keep the saved configuration. */
    fun disconnect() = connectionManager.disconnect()

    /** Erase the saved server entirely; the stream stops automatically. */
    fun clearServer() {
        viewModelScope.launch {
            connectionRepository.clearConfig()
            connectionManager.disconnect()
            _uiState.update { it.copy(latencyMs = null, serverVersion = null) }
        }
    }

    fun onErrorShown() = _uiState.update { it.copy(error = null) }

    private fun loadActiveModel() {
        viewModelScope.launch {
            modelRepository.fetchProviders()
                .onSuccess { providers ->
                    val current = providers.flatMap { it.models }.firstOrNull { it.isCurrent }
                    _uiState.update {
                        it.copy(
                            activeModel = current?.let { m -> "${m.providerId}/${m.modelId}" },
                        )
                    }
                }
        }
    }

    companion object {
        /** Never exposes the real token: 2 head chars + dots + 2 tail chars. */
        internal fun maskToken(token: String): String = when {
            token.isBlank() -> ""
            token.length <= 4 -> "••••••"
            else -> token.take(2) + "••••••" + token.takeLast(2)
        }
    }
}

package com.anomalyco.opencode.ui.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anomalyco.opencode.domain.model.SessionSummary
import com.anomalyco.opencode.domain.repository.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Immutable render state for [SessionListScreen]. */
data class SessionListUiState(
    val sessions: List<SessionSummary> = emptyList(),
    val isLoading: Boolean = true,
    val isCreating: Boolean = false,
    val error: String? = null,
    /** One-shot navigation signal, consumed via [SessionListViewModel.onSessionOpened]. */
    val createdSessionId: String? = null,
)

/**
 * Session list screen state: live cache observations plus refresh and
 * create-session intents. Errors surface through [SessionListUiState.error]
 * and are expected to be acknowledged by the snackbar host.
 */
@HiltViewModel
class SessionListViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SessionListUiState())
    val uiState: StateFlow<SessionListUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            sessionRepository.sessions.collect { list ->
                _uiState.update { it.copy(sessions = list, isLoading = false) }
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(error = null) }
            sessionRepository.refreshSessions()
                .onSuccess { _uiState.update { it.copy(isLoading = false) } }
                .onFailure { error ->
                    _uiState.update { it.copy(isLoading = false, error = error.message) }
                }
        }
    }

    /** Creates a fresh session and asks the navigation layer to open it. */
    fun createSession() {
        if (_uiState.value.isCreating) return
        viewModelScope.launch {
            _uiState.update { it.copy(isCreating = true) }
            sessionRepository.createSession()
                .onSuccess { session ->
                    _uiState.update {
                        it.copy(createdSessionId = session.id, isCreating = false)
                    }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(isCreating = false, error = error.message) }
                }
        }
    }

    fun onSessionOpened() = _uiState.update { it.copy(createdSessionId = null) }

    fun onErrorShown() = _uiState.update { it.copy(error = null) }
}

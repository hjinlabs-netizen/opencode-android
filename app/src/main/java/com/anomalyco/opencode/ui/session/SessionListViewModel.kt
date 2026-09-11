package com.anomalyco.opencode.ui.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anomalyco.opencode.domain.model.SessionSummary
import com.anomalyco.opencode.domain.repository.FileRepository
import com.anomalyco.opencode.domain.repository.SessionRepository
import com.anomalyco.opencode.domain.repository.WorkspaceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Outcome summary of a partial batch delete, formatted at the UI layer. */
data class DeleteReport(val deleted: Int, val total: Int)

/** Immutable render state for [SessionListScreen]. */
data class SessionListUiState(
    val sessions: List<SessionSummary> = emptyList(),
    val isLoading: Boolean = true,
    val isCreating: Boolean = false,
    val error: String? = null,
    /** Partial-failure summary from the last batch delete (P0-7). */
    val deleteReport: DeleteReport? = null,
    /** One-shot navigation signal, consumed via [SessionListViewModel.onSessionOpened]. */
    val createdSessionId: String? = null,
    /** --- new-session flow (Phase 5) --- */
    val showNewSessionOptions: Boolean = false,
    val showDirectoryDialog: Boolean = false,
    val directoryInput: String = "",
    /** Directory pre-validation state (P1): inline error instead of a 400 snackbar. */
    val isValidatingDirectory: Boolean = false,
    val directoryError: String? = null,
    /** --- multi-select deletion (Phase 5) --- */
    val selectionMode: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
    val showDeleteConfirm: Boolean = false,
    val isDeleting: Boolean = false,
)

/**
 * Session list screen state: live cache observations, quick/custom session
 * creation (with recent working directories), long-press multi-select and
 * confirmed batch deletion. Errors surface through [SessionListUiState.error].
 */
@HiltViewModel
class SessionListViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val fileRepository: FileRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SessionListUiState())
    val uiState: StateFlow<SessionListUiState> = _uiState.asStateFlow()

    /** Most recently used working directories, newest first. */
    val recentDirectories: StateFlow<List<String>> = workspaceRepository.recentDirectories
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch {
            sessionRepository.sessions.collect { list ->
                _uiState.update { state -> state.copy(sessions = list, isLoading = false) }
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

    // ---- new session flow ----------------------------------------------------

    fun showNewSessionOptions() = _uiState.update { it.copy(showNewSessionOptions = true) }

    fun dismissNewSessionOptions() = _uiState.update { it.copy(showNewSessionOptions = false) }

    fun showDirectoryDialog() = _uiState.update {
        it.copy(showNewSessionOptions = false, showDirectoryDialog = true)
    }

    fun dismissDirectoryDialog() = _uiState.update {
        it.copy(showDirectoryDialog = false, directoryInput = "", directoryError = null)
    }

    fun onDirectoryInputChange(value: String) = _uiState.update {
        it.copy(directoryInput = value, directoryError = null)
    }

    /** Quick create on the server default directory. */
    fun quickCreateSession() {
        _uiState.update { it.copy(showNewSessionOptions = false) }
        createSessionInternal(directory = null)
    }

    /**
     * Create bound to the typed/recently-picked working directory. The path is
     * first probed through the file endpoints (`/find` chain) so an invalid or
     * missing directory surfaces as an inline dialog error instead of an
     * HTTP 400 snackbar from `POST /session`.
     */
    fun createSessionWithDirectory() {
        val state = _uiState.value
        val directory = state.directoryInput.trim()
        if (directory.isEmpty() || state.isCreating || state.isValidatingDirectory) return
        viewModelScope.launch {
            _uiState.update { it.copy(isValidatingDirectory = true, directoryError = null) }
            val probe = fileRepository.listDirectory(directory)
            if (probe.isFailure) {
                _uiState.update {
                    it.copy(
                        isValidatingDirectory = false,
                        directoryError = probe.exceptionOrNull()?.message
                            ?: "Klasör doğrulanamadı — sunucu erişilebilir mi?",
                    )
                }
                return@launch
            }
            _uiState.update {
                it.copy(
                    isValidatingDirectory = false,
                    showDirectoryDialog = false,
                    directoryInput = "",
                )
            }
            createSessionInternal(directory)
        }
    }

    private fun createSessionInternal(directory: String?) {
        if (_uiState.value.isCreating) return
        viewModelScope.launch {
            _uiState.update { it.copy(isCreating = true) }
            sessionRepository.createSession(directory = directory)
                .onSuccess { session ->
                    if (!directory.isNullOrBlank()) {
                        workspaceRepository.rememberDirectory(directory)
                    }
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

    // ---- multi-select deletion -------------------------------------------------

    /** Row tap: open the session, or toggle its selection in selection mode. */
    fun onSessionClick(sessionId: String) {
        if (_uiState.value.selectionMode) {
            toggleSelection(sessionId)
        } else {
            _uiState.update { it.copy(createdSessionId = sessionId) }
        }
    }

    fun onSessionLongClick(sessionId: String) {
        _uiState.update {
            it.copy(selectionMode = true, selectedIds = it.selectedIds + sessionId)
        }
    }

    fun toggleSelection(sessionId: String) {
        _uiState.update { state ->
            val selected =
                if (sessionId in state.selectedIds) state.selectedIds - sessionId
                else state.selectedIds + sessionId
            state.copy(
                selectedIds = selected,
                // Dropping the last selection leaves selection mode.
                selectionMode = selected.isNotEmpty(),
            )
        }
    }

    fun selectAll() = _uiState.update {
        it.copy(selectionMode = true, selectedIds = it.sessions.map { s -> s.id }.toSet())
    }

    fun clearSelection() = _uiState.update {
        it.copy(selectionMode = false, selectedIds = emptySet())
    }

    fun requestDeleteSelection() {
        if (_uiState.value.selectedIds.isEmpty()) return
        _uiState.update { it.copy(showDeleteConfirm = true) }
    }

    fun cancelDelete() = _uiState.update { it.copy(showDeleteConfirm = false) }

    /**
     * Deletes every selected session. Individual failures never abort the
     * batch (P0-7): successes accumulate (the repository cache already drops
     * them optimistically) and a partial run reports "deleted X of Y" for
     * the UI to render. Failed rows stay visible.
     */
    fun confirmDelete() {
        val ids = _uiState.value.selectedIds
        if (ids.isEmpty() || _uiState.value.isDeleting) {
            _uiState.update { it.copy(showDeleteConfirm = false) }
            return
        }
        _uiState.update { it.copy(showDeleteConfirm = false, isDeleting = true) }
        viewModelScope.launch {
            var deletedCount = 0
            var failedCount = 0
            ids.forEach { id ->
                when {
                    sessionRepository.deleteSession(id).isSuccess -> deletedCount++
                    else -> failedCount++
                }
            }
            _uiState.update { state ->
                state.copy(
                    isDeleting = false,
                    selectionMode = false,
                    selectedIds = emptySet(),
                    deleteReport = DeleteReport(deleted = deletedCount, total = ids.size)
                        .takeIf { failedCount > 0 },
                )
            }
            if (failedCount == 0) refresh()
        }
    }

    fun onDeleteReportShown() = _uiState.update { it.copy(deleteReport = null) }
}

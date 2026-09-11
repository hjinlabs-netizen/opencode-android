package com.anomalyco.opencode.ui.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anomalyco.opencode.domain.model.FileDiff
import com.anomalyco.opencode.domain.repository.FileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Immutable render state for [DiffScreen]. */
data class DiffUiState(
    val files: List<FileDiff> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
)

/** Loads the working-tree diff (agent changes vs HEAD) for the diff viewer. */
@HiltViewModel
class DiffViewModel @Inject constructor(
    private val fileRepository: FileRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DiffUiState())
    val uiState: StateFlow<DiffUiState> = _uiState.asStateFlow()

    /** Indexes of collapsed files; defaults to the first file expanded. */
    private val _expanded = MutableStateFlow<Set<Int>>(setOf(0))
    val expanded: StateFlow<Set<Int>> = _expanded.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            fileRepository.workingTreeDiff()
                .onSuccess { diffs ->
                    _uiState.update { it.copy(files = diffs, isLoading = false) }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(isLoading = false, error = error.message) }
                }
        }
    }

    fun toggle(index: Int) {
        _expanded.update { if (index in it) it - index else it + index }
    }

    /**
     * Expand-all / collapse-all (Sprint B): expands everything when any file
     * is currently collapsed, otherwise collapses everything.
     */
    fun toggleAll() {
        _expanded.update { currentlyExpanded ->
            val allIndices = _uiState.value.files.indices.toSet()
            if (currentlyExpanded.isEmpty() || currentlyExpanded.size < allIndices.size) {
                allIndices
            } else {
                emptySet()
            }
        }
    }

    fun onErrorShown() = _uiState.update { it.copy(error = null) }
}

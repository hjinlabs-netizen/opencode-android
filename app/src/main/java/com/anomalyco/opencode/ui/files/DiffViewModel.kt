package com.anomalyco.opencode.ui.files

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anomalyco.opencode.domain.error.OpenCodeError
import com.anomalyco.opencode.domain.error.toDisplayError
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
    val error: OpenCodeError? = null,
)

/** Loads the working-tree diff (agent changes vs HEAD) for the diff viewer. */
@HiltViewModel
class DiffViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val fileRepository: FileRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DiffUiState())
    val uiState: StateFlow<DiffUiState> = _uiState.asStateFlow()

    /** Indexes of collapsed files; defaults to the first file expanded. */
    private val _expanded = MutableStateFlow<Set<Int>>(setOf(0))
    val expanded: StateFlow<Set<Int>> = _expanded.asStateFlow()

    /**
     * Optional per-file deep-link target (P1-1). Blank shows the whole
     * working tree; a path scopes the viewer to that single file's diff.
     */
    private val targetPath: String =
        savedStateHandle.get<String>(ARG_PATH).orEmpty().let { if (it == "null") "" else it }

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            if (targetPath.isBlank()) {
                fileRepository.workingTreeDiff()
                    .onSuccess { diffs ->
                        _uiState.update { it.copy(files = diffs, isLoading = false) }
                    }
                    .onFailure { error ->
                        _uiState.update { it.copy(isLoading = false, error = error.toDisplayError()) }
                    }
                return@launch
            }
            // A concrete target must resolve to that exact file. The repository
            // falls back to an unrelated row when the requested path is absent,
            // so re-verify the path here and never display the wrong file.
            fileRepository.diffFile(targetPath)
                .onSuccess { diff ->
                    if (diff.path == targetPath) {
                        _uiState.update { it.copy(files = listOf(diff), isLoading = false) }
                    } else {
                        _uiState.update {
                            it.copy(files = emptyList(), isLoading = false, error = OpenCodeError.Http(404))
                        }
                    }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(isLoading = false, error = error.toDisplayError()) }
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

    companion object {
        const val ARG_PATH = "path"
    }
}

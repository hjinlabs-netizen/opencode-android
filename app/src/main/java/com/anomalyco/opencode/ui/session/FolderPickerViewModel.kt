package com.anomalyco.opencode.ui.session

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anomalyco.opencode.domain.error.OpenCodeError
import com.anomalyco.opencode.domain.error.toDisplayError
import com.anomalyco.opencode.domain.model.FileListing
import com.anomalyco.opencode.domain.model.FileNode
import com.anomalyco.opencode.domain.model.ServerPath
import com.anomalyco.opencode.domain.repository.FileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Immutable render state for the future folder picker screen (W.3).
 *
 * Paths are RELATIVE server paths (W.0 contract): the server browses only
 * inside its project root, spelled [ServerPath.ROOT]. The currently browsed
 * directory is itself the primary selection candidate; [selectedFolder]
 * holds the confirmed pick for W.3 to hand to the new-session flow.
 */
data class FolderPickerUiState(
    /** Directory currently browsed; [ServerPath.ROOT] is the project root. */
    val currentPath: String = ServerPath.ROOT,
    /** Child directories only - files are never selectable (W.0/W.2 rule). */
    val directories: List<FileNode> = emptyList(),
    /** Ancestor chain root-first, always starting with the project root. */
    val breadcrumbs: List<String> = listOf(ServerPath.ROOT),
    val isLoading: Boolean = false,
    /** True when the server held more entries than the M.4 cap kept. */
    val listingTruncated: Boolean = false,
    /** Confirmed pick, or `null` while nothing is selected yet. */
    val selectedFolder: String? = null,
    /** Typed failure of the last listing attempt; rendered localized by W.3. */
    val error: OpenCodeError? = null,
) {
    val isAtRoot: Boolean get() = ServerPath.isRoot(currentPath)
    val canGoUp: Boolean get() = !isAtRoot
}

/**
 * Server-side folder browsing state for the Working Folder picker.
 *
 * Everything path-related routes through [ServerPath] (normalize/parentOf/
 * join) so the picker can never emit `..` traversal or absolute paths the
 * server rejects with HTTP 500 (W.0). Navigation is client-side: "back" is
 * just a re-list of the remembered parent level. Failures stay typed
 * [OpenCodeError] values - raw server text never reaches the state.
 */
@HiltViewModel
class FolderPickerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val fileRepository: FileRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FolderPickerUiState())
    val uiState: StateFlow<FolderPickerUiState> = _uiState.asStateFlow()

    init {
        val initial = savedStateHandle.get<String>(ARG_PATH)
        load(ServerPath.normalize(initial.orEmpty()))
    }

    /** Descend into a child directory entry (tap on a folder row). */
    fun enterFolder(node: FileNode) {
        load(ServerPath.normalize(node.path))
    }

    /**
     * Walk one breadcrumb level up via [ServerPath.parentOf]; at the project
     * root this is a no-op (no parent, no server call, no `..` - W.0 rule).
     */
    fun goUp() {
        val parent = ServerPath.parentOf(_uiState.value.currentPath) ?: return
        load(parent)
    }

    /** Jump directly to an ancestor from the breadcrumb strip. */
    fun goToBreadcrumb(path: String) {
        load(ServerPath.normalize(path))
    }

    /** Confirm the currently browsed directory as the working folder pick. */
    fun selectCurrentFolder() {
        _uiState.update { it.copy(selectedFolder = it.currentPath) }
    }

    /** Confirm a child directory as the pick without descending first. */
    fun selectFolder(node: FileNode) {
        _uiState.update { it.copy(selectedFolder = ServerPath.normalize(node.path)) }
    }

    fun onErrorShown() = _uiState.update { it.copy(error = null) }

    private fun load(path: String) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    currentPath = path,
                    breadcrumbs = ancestorsOf(path),
                    isLoading = true,
                    error = null,
                )
            }
            fileRepository.listDirectory(path)
                .onSuccess { listing ->
                    _uiState.update {
                        it.copy(
                            directories = listing.entries
                                .filter(FileNode::isDirectory)
                                .sortedBy { node -> node.name.lowercase() },
                            listingTruncated = listing.truncated,
                            isLoading = false,
                        )
                    }
                }
                .onFailure { error ->
                    // Keep the previous listing visible; the typed error
                    // drives the localized snackbar/notice in W.3.
                    _uiState.update { it.copy(isLoading = false, error = error.toDisplayError()) }
                }
        }
    }

    /** Root-first ancestor chain for [path], always containing the root. */
    private fun ancestorsOf(path: String): List<String> {
        val chain = ArrayDeque<String>()
        var cursor = path
        while (!ServerPath.isRoot(cursor)) {
            chain.addFirst(cursor)
            cursor = ServerPath.parentOf(cursor) ?: break
        }
        chain.addFirst(ServerPath.ROOT)
        return chain.toList()
    }

    companion object {
        const val ARG_PATH = "path"
    }
}

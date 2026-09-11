package com.anomalyco.opencode.ui.files

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anomalyco.opencode.domain.model.FileContent
import com.anomalyco.opencode.domain.model.FileNode
import com.anomalyco.opencode.domain.repository.FileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Immutable render state for [FileExplorerScreen]. */
data class FileExplorerUiState(
    /** Directory currently shown; "" is the server-side project root. */
    val path: String = "",
    val entries: List<FileNode> = emptyList(),
    /** Parent chain for the back/breadcrumb affordance. */
    val breadcrumbs: List<String> = emptyList(),
    val isLoading: Boolean = false,
    /** When non-null, a file preview covers the listing. */
    val openFile: FileContent? = null,
    val isReadingFile: Boolean = false,
    val error: String? = null,
)

/**
 * Browse-only file explorer over the server's project view: tap a directory
 * to descend, a file to preview its text content. Navigation is internal
 * (path stack) instead of a nav-graph entry per directory.
 */
@HiltViewModel
class FileExplorerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val fileRepository: FileRepository,
) : ViewModel() {

    private val initialPath: String =
        savedStateHandle.get<String>(ARG_PATH).orEmpty().let {
            if (it == "null") "" else it
        }

    private val _uiState = MutableStateFlow(FileExplorerUiState(path = initialPath))
    val uiState: StateFlow<FileExplorerUiState> = _uiState.asStateFlow()

    init {
        // Arriving with a path (e.g. the session's working directory) lists
        // that directory; blank lists the server default root.
        loadDirectory(initialPath)
    }

    fun onNodeClick(node: FileNode) {
        if (node.isDirectory) {
            _uiState.update { it.copy(breadcrumbs = it.breadcrumbs + it.path, path = node.path) }
            loadDirectory(node.path)
        } else {
            loadFile(node.path)
        }
    }

    /** Pop one directory level (also used by the system back when at root). */
    fun navigateUp(): Boolean {
        val crumbs = _uiState.value.breadcrumbs
        val target = crumbs.lastOrNull() ?: return false
        _uiState.update {
            it.copy(breadcrumbs = crumbs.dropLast(1), path = target, openFile = null)
        }
        loadDirectory(target)
        return true
    }

    fun closeFile() = _uiState.update { it.copy(openFile = null) }

    fun onErrorShown() = _uiState.update { it.copy(error = null) }

    private fun loadDirectory(path: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            fileRepository.listDirectory(path)
                .onSuccess { nodes ->
                    _uiState.update {
                        it.copy(entries = nodes.sortedWithDirectoryFirst(), isLoading = false)
                    }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(isLoading = false, error = error.message) }
                }
        }
    }

    private fun loadFile(path: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isReadingFile = true, error = null) }
            fileRepository.readFile(path)
                .onSuccess { file ->
                    _uiState.update { it.copy(openFile = file, isReadingFile = false) }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(isReadingFile = false, error = error.message) }
                }
        }
    }

    companion object {
        const val ARG_PATH = "path"

        private fun List<FileNode>.sortedWithDirectoryFirst(): List<FileNode> =
            sortedWith(
                compareByDescending<FileNode> { it.isDirectory }
                    .thenBy { it.name.lowercase() },
            )
    }
}

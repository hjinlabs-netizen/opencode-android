package com.anomalyco.opencode.ui.session

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anomalyco.opencode.R
import com.anomalyco.opencode.data.PayloadLimits
import com.anomalyco.opencode.domain.model.FileNode
import com.anomalyco.opencode.domain.model.ServerPath
import com.anomalyco.opencode.ui.common.stringForError

/**
 * Server-side working-folder browser (Sprint W.3). Directories only; tap
 * descends, the up/back button walks the client-side breadcrumb (the server
 * forbids `..` traversal - W.0), "Choose this folder" confirms the browsed
 * directory and hands the resolved path (server absolute preferred, relative
 * fallback) back through the navigation result.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderPickerScreen(
    onBack: () -> Unit,
    onPick: (String) -> Unit,
    viewModel: FolderPickerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val errorText = state.error?.let { stringForError(it) }
    LaunchedEffect(state.error) {
        if (errorText != null) {
            snackbarHostState.showSnackbar(errorText)
            viewModel.onErrorShown()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = pathLabel(state.currentPath),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = { if (state.canGoUp) viewModel.goUp() else onBack() },
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = { viewModel.selectCurrentFolder()?.let(onPick) },
                        enabled = !state.isLoading,
                    ) {
                        Text(stringResource(R.string.picker_choose))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            BreadcrumbStrip(state.breadcrumbs, onSelect = viewModel::goToBreadcrumb)
            when {
                state.isLoading && state.directories.isEmpty() -> Centered { CircularProgressIndicator() }

                state.directories.isEmpty() && state.error == null -> Centered {
                    Text(
                        text = stringResource(R.string.picker_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                state.directories.isEmpty() && state.error != null -> Centered {
                    // W.0: the server answers 500 for unopenable paths; the
                    // typed detail already went to the snackbar - this is the
                    // calm, localized in-place state.
                    Text(
                        text = stringResource(R.string.picker_cannot_open),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(state.directories, key = { it.path }) { node ->
                        FolderRow(node = node, onClick = { viewModel.enterFolder(node) })
                    }
                    if (state.listingTruncated) {
                        item {
                            Text(
                                text = pluralStringResource(
                                    R.plurals.files_list_truncated,
                                    PayloadLimits.MAX_LIST_NODES,
                                    PayloadLimits.MAX_LIST_NODES,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun BreadcrumbStrip(breadcrumbs: List<String>, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        breadcrumbs.forEachIndexed { index, crumb ->
            if (index > 0) {
                Text(
                    text = "/",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { onSelect(crumb) }) {
                Text(
                    text = pathLabel(crumb),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun FolderRow(node: FileNode, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Folder,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.secondary,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = node.name.ifBlank { node.path },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Breadcrumb/title label: localized root name, else the last path segment. */
@Composable
private fun pathLabel(path: String): String =
    if (ServerPath.isRoot(path)) {
        stringResource(R.string.picker_project_root)
    } else {
        path.substringAfterLast('\\').substringAfterLast('/')
    }

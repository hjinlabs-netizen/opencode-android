package com.anomalyco.opencode.ui.files

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anomalyco.opencode.R
import com.anomalyco.opencode.data.PayloadLimits
import com.anomalyco.opencode.domain.model.FileNode
import com.anomalyco.opencode.ui.common.stringForError
import com.anomalyco.opencode.ui.theme.Warning

/**
 * Project file browser. Tapping a directory descends, a file opens the
 * text-preview overlay; system back walks up the path stack before leaving.
 * [onAddToChat], when provided (chat-originated navigation), inserts the
 * previewed file into the chat prompt as an `@path` context mention.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileExplorerScreen(
    onBack: () -> Unit,
    onAddToChat: ((String) -> Unit)? = null,
    viewModel: FileExplorerViewModel = hiltViewModel(),
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
    BackHandler(enabled = state.openFile != null || state.breadcrumbs.isNotEmpty()) {
        when {
            state.openFile != null -> viewModel.closeFile()
            else -> if (!viewModel.navigateUp()) onBack()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.openFile?.path
                            ?: state.path.ifBlank { stringResource(R.string.files_title) },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        when {
                            state.openFile != null -> viewModel.closeFile()
                            state.breadcrumbs.isNotEmpty() -> viewModel.navigateUp()
                            else -> onBack()
                        }
                    }) {
                        Icon(
                            imageVector = if (state.openFile != null) {
                                Icons.Filled.Close
                            } else {
                                Icons.AutoMirrored.Filled.ArrowBack
                            },
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    val open = state.openFile
                    if (open != null && onAddToChat != null) {
                        IconButton(onClick = { onAddToChat(open.path) }) {
                            Icon(
                                imageVector = Icons.Filled.AddComment,
                                contentDescription = stringResource(R.string.files_add_to_chat),
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        val file = state.openFile
        when {
            file != null -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp),
            ) {
                if (file.truncated) {
                    Text(
                        text = stringResource(R.string.preview_truncated),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                Text(
                    text = file.content.ifBlank { stringResource(R.string.files_empty_file) },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            state.isLoading && state.entries.isEmpty() -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            state.isReadingFile -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            else -> {
                val visible = filterFileNodes(state.entries, state.query)
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) {
                    if (state.entries.isNotEmpty()) {
                        OutlinedTextField(
                            value = state.query,
                            onValueChange = viewModel::onQueryChange,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                            placeholder = { Text(stringResource(R.string.files_search_hint)) },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                            trailingIcon = {
                                if (state.query.isNotEmpty()) {
                                    IconButton(onClick = viewModel::clearQuery) {
                                        Icon(
                                            imageVector = Icons.Filled.Close,
                                            contentDescription = stringResource(R.string.files_search_clear),
                                        )
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                        )
                    }
                    if (state.listingTruncated) {
                        // Sprint M.4: honest notice that the folder holds more
                        // than the memory cap keeps.
                        Text(
                            text = pluralStringResource(
                                R.plurals.files_list_truncated,
                                PayloadLimits.MAX_LIST_NODES,
                                PayloadLimits.MAX_LIST_NODES,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = Warning,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(visible, key = { it.path }) { node ->
                            FileNodeRow(node = node, onClick = { viewModel.onNodeClick(node) })
                        }
                        if (visible.isEmpty()) {
                            item {
                                Text(
                                    text = stringResource(
                                        if (state.query.isNotBlank() && state.entries.isNotEmpty()) {
                                            R.string.model_picker_no_match
                                        } else {
                                            R.string.files_empty_folder
                                        },
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileNodeRow(node: FileNode, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
        ) {
            Icon(
                imageVector = if (node.isDirectory) {
                    Icons.Filled.Folder
                } else {
                    Icons.AutoMirrored.Filled.InsertDriveFile
                },
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (node.isDirectory) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = node.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!node.isDirectory && node.size > 0) {
                Text(
                    text = formatSize(node.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1_048_576 -> "${bytes / 1024} KB"
    else -> "${bytes / 1_048_576} MB"
}

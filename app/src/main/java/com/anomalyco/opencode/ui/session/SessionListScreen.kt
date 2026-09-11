package com.anomalyco.opencode.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anomalyco.opencode.R
import com.anomalyco.opencode.domain.model.SessionSummary
import com.anomalyco.opencode.ui.common.relativeTimeText

/**
 * Lists server sessions; the FAB opens the quick/custom-directory creation
 * flow, rows long-press into multi-select with confirmed batch deletion.
 * Selecting a row (or finishing creation) opens the chat room.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    onOpenChat: (String) -> Unit,
    onOpenSettings: () -> Unit = {},
    viewModel: SessionListViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val recents by viewModel.recentDirectories.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onErrorShown()
        }
    }
    LaunchedEffect(state.deleteReport) {
        state.deleteReport?.let { report ->
            val message = context.getString(
                R.string.sessions_delete_report,
                report.deleted,
                report.total,
            )
            snackbarHostState.showSnackbar(message)
            viewModel.onDeleteReportShown()
        }
    }
    LaunchedEffect(state.createdSessionId) {
        state.createdSessionId?.let {
            viewModel.onSessionOpened()
            onOpenChat(it)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            if (state.selectionMode) {
                SelectionTopBar(
                    selectedCount = state.selectedIds.size,
                    isDeleting = state.isDeleting,
                    onClear = viewModel::clearSelection,
                    onSelectAll = viewModel::selectAll,
                    onDelete = viewModel::requestDeleteSelection,
                )
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.sessions_title)) },
                    actions = {
                        IconButton(onClick = viewModel::refresh) {
                            Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_refresh))
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.action_settings))
                        }
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (!state.selectionMode) {
                ExtendedFloatingActionButton(
                    onClick = viewModel::showNewSessionOptions,
                    icon = {
                        if (state.isCreating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Icon(Icons.Filled.Add, contentDescription = null)
                        }
                    },
                    text = {
                        Text(
                            stringResource(
                                if (state.isCreating) R.string.sessions_creating else R.string.sessions_new,
                            ),
                        )
                    },
                )
            }
        },
    ) { innerPadding ->
        when {
            state.isLoading && state.sessions.isEmpty() -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            state.sessions.isEmpty() -> EmptySessions(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.sessions, key = { it.id }) { session ->
                    SessionRow(
                        session = session,
                        selectionMode = state.selectionMode,
                        selected = session.id in state.selectedIds,
                        onClick = { viewModel.onSessionClick(session.id) },
                        onLongClick = { viewModel.onSessionLongClick(session.id) },
                        onToggle = { viewModel.toggleSelection(session.id) },
                    )
                }
            }
        }
    }

    if (state.showNewSessionOptions) {
        NewSessionOptionsDialog(
            onQuickCreate = viewModel::quickCreateSession,
            onCustomDirectory = viewModel::showDirectoryDialog,
            onDismiss = viewModel::dismissNewSessionOptions,
        )
    }

    if (state.showDirectoryDialog) {
        DirectoryDialog(
            value = state.directoryInput,
            recents = recents,
            isValidating = state.isValidatingDirectory,
            error = state.directoryError,
            onValueChange = viewModel::onDirectoryInputChange,
            onPickRecent = viewModel::onDirectoryInputChange,
            onCreate = viewModel::createSessionWithDirectory,
            onDismiss = viewModel::dismissDirectoryDialog,
        )
    }

    if (state.showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = viewModel::cancelDelete,
            icon = { Icon(Icons.Filled.Delete, contentDescription = null) },
            title = { Text(stringResource(R.string.sessions_delete_title)) },
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.sessions_delete_message,
                        state.selectedIds.size,
                        state.selectedIds.size,
                    ),
                )
            },
            confirmButton = {
                Button(onClick = viewModel::confirmDelete) {
                    Text(stringResource(R.string.sessions_delete_confirm), color = MaterialTheme.colorScheme.onError)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelDelete) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    selectedCount: Int,
    isDeleting: Boolean,
    onClear: () -> Unit,
    onSelectAll: () -> Unit,
    onDelete: () -> Unit,
) {
    TopAppBar(
        title = {
            Text(
                pluralStringResource(
                    R.plurals.sessions_selected_count,
                    selectedCount,
                    selectedCount,
                ),
            )
        },
        navigationIcon = {
            IconButton(onClick = onClear) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.sessions_exit_selection))
            }
        },
        actions = {
            if (isDeleting) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .size(18.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                IconButton(onClick = onSelectAll) {
                    Icon(Icons.Filled.SelectAll, contentDescription = stringResource(R.string.sessions_select_all))
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.sessions_delete_selected),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
    )
}

@Composable
private fun SessionRow(
    session: SessionSummary,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggle: () -> Unit,
) {
    Surface(
        tonalElevation = if (selected) 2.dp else 0.dp,
        shape = RoundedCornerShape(18.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        else MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionMode) {
                Checkbox(checked = selected, onCheckedChange = { onToggle() })
                Spacer(Modifier.width(6.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.title.ifBlank { stringResource(R.string.sessions_default_title) },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.size(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    session.agent?.takeIf { it.isNotBlank() }?.let { agent ->
                        Text(
                            text = agent,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    Text(
                        text = relativeTimeText(session.updatedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!selectionMode) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun NewSessionOptionsDialog(
    onQuickCreate: () -> Unit,
    onCustomDirectory: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Chat, contentDescription = null) },
        title = { Text(stringResource(R.string.new_session_title)) },
        text = {
            Text(stringResource(R.string.new_session_description))
        },
        confirmButton = {
            Button(onClick = onQuickCreate) { Text(stringResource(R.string.new_session_quick)) }
        },
        dismissButton = {
            OutlinedButton(onClick = onCustomDirectory) {
                Icon(
                    imageVector = Icons.Filled.FolderOpen,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.new_session_custom))
            }
        },
    )
}

@Composable
private fun DirectoryDialog(
    value: String,
    recents: List<String>,
    isValidating: Boolean,
    error: String?,
    onValueChange: (String) -> Unit,
    onPickRecent: (String) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.directory_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.directory_label)) },
                    placeholder = { Text(stringResource(R.string.directory_placeholder)) },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                )
                if (recents.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.directory_recent),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    recents.forEach { dir ->
                        Text(
                            text = dir,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .combinedClickable(
                                    onClick = { onPickRecent(dir) },
                                    onLongClick = { onPickRecent(dir) },
                                )
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onCreate, enabled = value.isNotBlank() && !isValidating) {
                if (isValidating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.directory_validating))
                } else {
                    Text(stringResource(R.string.directory_create))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun EmptySessions(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Chat,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = stringResource(R.string.sessions_empty_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.sessions_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

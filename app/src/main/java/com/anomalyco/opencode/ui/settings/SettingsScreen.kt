package com.anomalyco.opencode.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anomalyco.opencode.R
import com.anomalyco.opencode.domain.model.ConnectionState
import com.anomalyco.opencode.domain.model.ThemeMode
import com.anomalyco.opencode.ui.common.stringForError
import com.anomalyco.opencode.ui.theme.Danger
import com.anomalyco.opencode.ui.theme.Success
import com.anomalyco.opencode.ui.theme.Warning

/**
 * Settings hub: server info + management, theme preference and diagnostics.
 * [onManageConnection] jumps to the connection screen to switch servers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onManageConnection: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var confirmErase by remember { mutableStateOf(false) }

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
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ServerCard(
                state = state,
                onCheckHealth = viewModel::runHealthCheck,
                onManageConnection = onManageConnection,
                onDisconnect = viewModel::disconnect,
                onEraseRequested = { confirmErase = true },
            )
            ThemeCard(current = state.themeMode, onSelect = viewModel::setThemeMode)
            DiagnosticsCard(state = state)
        }
    }

    if (confirmErase) {
        AlertDialog(
            onDismissRequest = { confirmErase = false },
            title = { Text(stringResource(R.string.settings_erase_title)) },
            text = { Text(stringResource(R.string.settings_erase_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmErase = false
                        viewModel.clearServer()
                    },
                ) {
                    Text(stringResource(R.string.sessions_delete_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmErase = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun ServerCard(
    state: SettingsUiState,
    onCheckHealth: () -> Unit,
    onManageConnection: () -> Unit,
    onDisconnect: () -> Unit,
    onEraseRequested: () -> Unit,
) {
    SectionCard(stringResource(R.string.settings_server_section)) {
        Column {
            Text(
                text = state.serverUrl.ifBlank { stringResource(R.string.settings_no_server) },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
            Text(
                text = if (state.hasToken) {
                    stringResource(R.string.settings_token, state.tokenMasked)
                } else {
                    stringResource(R.string.settings_token_none)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val connection = state.connection
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Text(
                    text = "●",
                    color = when (connection) {
                        is ConnectionState.Connected -> Success
                        is ConnectionState.Error -> Danger
                        ConnectionState.Connecting -> Warning
                        ConnectionState.Disconnected -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    text = when (connection) {
                        is ConnectionState.Connected -> stringResource(R.string.settings_state_connected)
                        is ConnectionState.Error ->
                            stringResource(R.string.settings_state_error, stringForError(connection.error))
                        ConnectionState.Connecting -> stringResource(R.string.settings_state_connecting)
                        ConnectionState.Disconnected -> stringResource(R.string.settings_state_disconnected)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCheckHealth, enabled = !state.isCheckingHealth && state.serverUrl.isNotBlank()) {
                if (state.isCheckingHealth) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(6.dp))
                }
                Text(stringResource(R.string.settings_health_check))
            }
            OutlinedButton(onClick = onManageConnection) { Text(stringResource(R.string.settings_manage_connection)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onDisconnect) { Text(stringResource(R.string.settings_disconnect)) }
            TextButton(onClick = onEraseRequested) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.size(4.dp))
                Text(stringResource(R.string.settings_erase), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ThemeCard(current: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    SectionCard(stringResource(R.string.settings_appearance_section)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { mode ->
                FilterChip(
                    selected = mode == current,
                    onClick = { onSelect(mode) },
                    label = {
                        Text(
                            text = stringResource(
                                when (mode) {
                                    ThemeMode.SYSTEM -> R.string.settings_theme_system
                                    ThemeMode.DARK -> R.string.settings_theme_dark
                                    ThemeMode.LIGHT -> R.string.settings_theme_light
                                },
                            ),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun DiagnosticsCard(state: SettingsUiState) {
    SectionCard(stringResource(R.string.settings_diagnostics_section)) {
        DiagnosticRow(stringResource(R.string.settings_app_version), state.appVersion)
        DiagnosticRow(
            stringResource(R.string.settings_server_version),
            state.serverVersion ?: stringResource(R.string.unknown),
        )
        DiagnosticRow(
            stringResource(R.string.settings_latency),
            state.latencyMs?.let { stringResource(R.string.settings_latency_value, it) }
                ?: stringResource(R.string.settings_latency_unmeasured),
        )
        DiagnosticRow(
            stringResource(R.string.settings_active_model),
            state.activeModel ?: stringResource(R.string.unknown),
        )
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

package com.anomalyco.opencode.ui.connection

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anomalyco.opencode.domain.model.ConnectionState

/**
 * Dashboard / Connection screen (Phase 1 entry point).
 *
 * Lets the user enter the OpenCode server URL and access token, test the
 * connection with a live health check, and persist everything encrypted.
 */
@Composable
fun ConnectionScreen(
    onOpenSessions: () -> Unit = {},
    viewModel: ConnectionViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Advance to the session list when a probe (manual or auto-resume) succeeds.
    // Seeding with the current state prevents a bounce when the user returns
    // to an already-connected screen via back navigation.
    var wasConnected by remember {
        mutableStateOf(uiState.connection is ConnectionState.Connected)
    }
    LaunchedEffect(uiState.connection) {
        val connected = uiState.connection is ConnectionState.Connected
        if (connected && !wasConnected) {
            wasConnected = true
            onOpenSessions()
        } else if (!connected) {
            wasConnected = false
        }
    }

    ConnectionScreenContent(
        state = uiState,
        onUrlChange = viewModel::onUrlChange,
        onTokenChange = viewModel::onTokenChange,
        onTestAndSave = viewModel::testAndSave,
        onDisconnect = viewModel::disconnect,
        onOpenSessions = onOpenSessions,
    )
}

@Composable
fun ConnectionScreenContent(
    state: ConnectionUiState,
    onUrlChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onTestAndSave: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenSessions: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Header
        Text(
            text = "OpenCode",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "Android thin client — OpenCode sunucunuza bağlanın",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                OutlinedTextField(
                    value = state.url,
                    onValueChange = onUrlChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Sunucu adresi") },
                    placeholder = { Text("http://192.168.1.10:4096") },
                    leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next,
                    ),
                )

                OutlinedTextField(
                    value = state.token,
                    onValueChange = onTokenChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Token / şifre (opsiyonel)") },
                    leadingIcon = { Icon(Icons.Filled.Key, contentDescription = null) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                )
            }
        }

        // Connection status card
        ConnectionStatusCard(state.connection)

        // Actions
        val busy = state.connection is ConnectionState.Connecting || state.saving
        Button(
            onClick = onTestAndSave,
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && state.url.isNotBlank(),
            shape = RoundedCornerShape(16.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(8.dp))
            } else {
                Icon(Icons.Filled.Sync, contentDescription = null)
                Spacer(Modifier.width(8.dp))
            }
            Text(if (busy) "Bağlanıyor…" else "Bağlantıyı Test Et & Kaydet")
        }

        if (state.connection is ConnectionState.Connected) {
            Button(
                onClick = onOpenSessions,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Oturumlara Geç")
            }
            OutlinedButton(
                onClick = onDisconnect,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text("Bağlantıyı Kes")
            }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Sunucu: `opencode serve --port 4096 --password <şifre>`\nveya masaüstü/CLI ile yerel ağınızda çalıştırın.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Color-coded status card that exhaustively renders every [ConnectionState].
 */
@Composable
private fun ConnectionStatusCard(connection: ConnectionState) {
    val (color, emoji, title, detail) = when (connection) {
        is ConnectionState.Disconnected ->
            Status(
                MaterialTheme.colorScheme.surfaceVariant,
                "🔌",
                "Bağlı değil",
                "Sunucu bilgilerini girip bağlantıyı test edin.",
            )
        is ConnectionState.Connecting ->
            Status(
                MaterialTheme.colorScheme.surfaceVariant,
                "⏳",
                "Bağlanıyor…",
                "Sunucu sağlık kontrolü yapılıyor.",
            )
        is ConnectionState.Connected ->
            Status(
                Color(0xFF34D399),
                "✅",
                "Bağlandı",
                connection.info.version?.let { "Sunucu sürümü: $it" } ?: "Sunucu yanıt verdi.",
            )
        is ConnectionState.Error ->
            Status(
                Color(0xFFFB5468),
                "❌",
                "Bağlantı hatası",
                connection.message,
            )
    }

    val bg by animateColorAsState(targetValue = color.copy(alpha = 0.12f), label = "status-bg")

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = bg),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = emoji, style = MaterialTheme.typography.titleLarge)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = color.takeIf { it != MaterialTheme.colorScheme.surfaceVariant }
                        ?: MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private data class Status(
    val color: Color,
    val emoji: String,
    val title: String,
    val detail: String,
)

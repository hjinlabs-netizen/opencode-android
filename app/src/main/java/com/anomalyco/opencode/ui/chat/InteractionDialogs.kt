package com.anomalyco.opencode.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.anomalyco.opencode.domain.model.PermissionDecision
import com.anomalyco.opencode.domain.model.PermissionRequest
import com.anomalyco.opencode.domain.model.QuestionRequest

/**
 * Material 3 dialog for a pending [PermissionRequest]: shows the proposed
 * path/command/diff and offers Deny / Allow / Allow-always. Dismissal is
 * treated as "not now" (the request stays queued), never an implicit deny.
 */
@Composable
fun PermissionDialog(
    request: PermissionRequest,
    onDecision: (PermissionDecision) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = { Text("İzin isteniyor") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = buildString {
                        request.type.takeIf { it.isNotBlank() }?.let { append("Tür: $it · ") }
                        append(request.description.ifBlank { "Bu işlemeye izin verilsin mi?" })
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                CodePreview("Dosya", request.path)
                CodePreview("Komut", request.command)
                CodePreview("Fark", request.diff, tall = true)
            }
        },
        confirmButton = {
            Button(onClick = { onDecision(PermissionDecision.ALLOW) }) {
                Text("İzin ver")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = { onDecision(PermissionDecision.ALLOW_ALWAYS) }) {
                    Text("Her zaman")
                }
                TextButton(onClick = { onDecision(PermissionDecision.DENY) }) {
                    Text("Reddet", color = MaterialTheme.colorScheme.error)
                }
            }
        },
    )
}

@Composable
private fun CodePreview(label: String, value: String?, tall: Boolean = false) {
    if (value.isNullOrBlank()) return
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .padding(top = 2.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .let { if (tall) it.heightIn(max = 160.dp) else it }
                .verticalScroll(rememberScrollState())
                .padding(8.dp),
        )
    }
}

/**
 * Question dialog: renders predefined options as single- or multi-select
 * chips, optionally a free-text field, and composes the answer list sent to
 * the server.
 */
@Composable
fun QuestionDialog(
    request: QuestionRequest,
    onAnswer: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val selected = remember(request.questionId) { mutableStateOf(setOf<String>()) }
    var custom by remember(request.questionId) { mutableStateOf("") }

    fun toggle(option: String) {
        selected.value = if (request.multiple) {
            if (option in selected.value) selected.value - option else selected.value + option
        } else {
            if (option in selected.value) emptySet() else setOf(option)
        }
    }

    val answers = buildList {
        addAll(selected.value)
        custom.takeIf { it.isNotBlank() && request.allowCustomAnswer }?.let { add(it.trim()) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
            )
        },
        title = { Text("Soru") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(request.text, style = MaterialTheme.typography.bodyLarge)

                if (request.options.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (request.multiple) {
                            request.options.forEach { option ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { toggle(option) },
                                ) {
                                    Checkbox(
                                        checked = option in selected.value,
                                        onCheckedChange = { toggle(option) },
                                    )
                                    Text(option, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        } else {
                            request.options.forEach { option ->
                                FilterChip(
                                    selected = option in selected.value,
                                    onClick = { toggle(option) },
                                    label = { Text(option) },
                                )
                            }
                        }
                    }
                }

                if (request.allowCustomAnswer) {
                    OutlinedTextField(
                        value = custom,
                        onValueChange = { custom = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Kendi cevabın (isteğe bağlı)") },
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onAnswer(answers) },
                enabled = answers.isNotEmpty(),
            ) {
                Text("Gönder")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Sonra") }
        },
    )
}

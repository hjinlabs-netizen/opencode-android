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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.anomalyco.opencode.R
import com.anomalyco.opencode.data.PayloadLimits
import com.anomalyco.opencode.data.remote.UnifiedDiffParser
import com.anomalyco.opencode.domain.model.FileDiff
import com.anomalyco.opencode.domain.model.PermissionDecision
import com.anomalyco.opencode.domain.model.PermissionRequest
import com.anomalyco.opencode.domain.model.QuestionRequest
import com.anomalyco.opencode.ui.files.DiffFileCard

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
        title = { Text(stringResource(R.string.permission_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = (request.type.takeIf { it.isNotBlank() }
                        ?.let { stringResource(R.string.permission_type, it) } ?: "") +
                        request.description.ifBlank { stringResource(R.string.permission_description) },
                    style = MaterialTheme.typography.bodyMedium,
                )
                CodePreview(stringResource(R.string.permission_file), request.path)
                CodePreview(stringResource(R.string.permission_command), request.command)
                PermissionDiff(request.diff)
            }
        },
        confirmButton = {
            Button(onClick = { onDecision(PermissionDecision.ALLOW) }) {
                Text(stringResource(R.string.permission_allow))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = { onDecision(PermissionDecision.ALLOW_ALWAYS) }) {
                    Text(stringResource(R.string.permission_allow_always))
                }
                TextButton(onClick = { onDecision(PermissionDecision.DENY) }) {
                    Text(stringResource(R.string.permission_deny), color = MaterialTheme.colorScheme.error)
                }
            }
        },
    )
}

/**
 * P1-4: render the proposed diff with the structured, color-coded
 * [DiffFileCard] (the same renderer the file-diff screen uses) instead of a
 * raw monospace block, so an agent's pending edit is scannable at a glance.
 * Falls back to plain [CodePreview] whenever the patch is blank, unparseable,
 * or not a unified diff, so nothing that previously rendered can disappear.
 */
@Composable
private fun PermissionDiff(diff: String?) {
    if (diff.isNullOrBlank()) return
    val parsed = remember(diff) { parsePermissionDiff(diff) }
    var expanded by remember(diff) { mutableStateOf(true) }

    if (parsed == null) {
        CodePreview(stringResource(R.string.permission_diff), diff, tall = true)
        return
    }

    Column {
        Text(
            text = stringResource(R.string.permission_diff),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DiffFileCard(
            diff = parsed,
            expanded = expanded,
            onToggle = { expanded = !expanded },
            modifier = Modifier
                .padding(top = 2.dp)
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState()),
        )
    }
}

/**
 * Cap the raw patch at [PayloadLimits.MAX_PATCH_CHARS] BEFORE parsing (so a
 * huge diff cannot explode into an unbounded line graph - mirroring the
 * FileRepository / Sprint M.3 boundary), then parse it into a render-ready
 * [FileDiff] carrying the truncation flag. Returns null to signal "fall back
 * to plain text": blank input, a non-diff payload, or a parse that yields no
 * hunks. Kept as a pure function so it is unit-testable off the UI thread.
 */
internal fun parsePermissionDiff(raw: String?): FileDiff? {
    if (raw.isNullOrBlank()) return null
    val capped = PayloadLimits.patch(raw)
    return UnifiedDiffParser.parseSingle(capped.text)
        ?.takeIf { it.hunks.isNotEmpty() }
        ?.copy(truncated = capped.truncated)
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
        title = { Text(stringResource(R.string.question_title)) },
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
                        label = { Text(stringResource(R.string.question_custom_hint)) },
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
                Text(stringResource(R.string.action_send))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.question_later)) }
        },
    )
}

package com.anomalyco.opencode.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anomalyco.opencode.R
import com.anomalyco.opencode.domain.model.MessagePart
import com.anomalyco.opencode.domain.model.StepState
import com.anomalyco.opencode.domain.model.ToolStatus
import com.anomalyco.opencode.ui.common.MarkdownText
import com.anomalyco.opencode.ui.theme.Danger
import com.anomalyco.opencode.ui.theme.Success
import com.anomalyco.opencode.ui.theme.Warning

/**
 * Renders any [MessagePart] as its polymorphic card. This is the single place
 * that knows how each sealed subtype maps to UI, so new part kinds fail at
 * compile time (non-exhaustive `when`) instead of at runtime.
 */
@Composable
fun MessagePartCard(
    part: MessagePart,
    modifier: Modifier = Modifier,
    onCodeCopied: (String) -> Unit = {},
) {
    when (part) {
        is MessagePart.TextPart -> TextPartCard(part, modifier, onCodeCopied)
        is MessagePart.ReasoningPart -> ReasoningPartCard(part, modifier)
        is MessagePart.ToolCallPart -> ToolCallPartCard(part, modifier)
        is MessagePart.ShellPart -> ShellPartCard(part, modifier)
        is MessagePart.StepPart -> StepPartCard(part, modifier)
    }
}

// ---- Text ------------------------------------------------------------------

@Composable
fun TextPartCard(
    part: MessagePart.TextPart,
    modifier: Modifier = Modifier,
    onCodeCopied: (String) -> Unit = {},
) {
    if (part.content.isEmpty()) return
    MarkdownText(text = part.content, modifier = modifier.fillMaxWidth(), onCodeCopied = onCodeCopied)
}

// ---- Reasoning (collapsible "thinking") ------------------------------------

@Composable
fun ReasoningPartCard(part: MessagePart.ReasoningPart, modifier: Modifier = Modifier) {
    var expanded by remember(part.id) { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Psychology,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.tertiary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.part_thinking),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (!part.isFinished) {
                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
                Spacer(Modifier.width(6.dp))
            }
            IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = stringResource(
                        if (expanded) R.string.part_collapse else R.string.part_expand,
                    ),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
            Text(
                text = part.thinking,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

// ---- Tool call (status chip) -----------------------------------------------

@Composable
fun ToolCallPartCard(part: MessagePart.ToolCallPart, modifier: Modifier = Modifier) {
    var expanded by remember(part.callId) { mutableStateOf(false) }
    val hasArgs = part.args.isNotBlank() && part.args != "{}"
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .then(if (hasArgs) Modifier.clickable { expanded = !expanded } else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Build,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.secondary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = part.toolName.ifBlank { part.callId },
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            ToolStatusChip(status = part.status)
            if (hasArgs) {
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = stringResource(R.string.part_arguments),
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        AnimatedVisibility(visible = expanded && hasArgs) {
            Column {
                Text(
                    text = part.args,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .fillMaxWidth()
                        .heightIn(max = 140.dp)
                        .verticalScroll(rememberScrollState()),
                )
                if (part.argsTruncated) {
                    TruncatedChip()
                }
            }
        }
    }
}

/** Localized notice rendered whenever a payload was cut at a memory limit. */
@Composable
private fun TruncatedChip(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.content_truncated),
        style = MaterialTheme.typography.labelSmall,
        color = Warning,
        modifier = modifier.padding(top = 4.dp),
    )
}

@Composable
private fun ToolStatusChip(status: ToolStatus) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(statusContainerColor(status))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        if (status == ToolStatus.RUNNING) {
            CircularProgressIndicator(
                modifier = Modifier.size(10.dp),
                strokeWidth = 1.5.dp,
                color = statusAccentColor(status),
            )
            Spacer(Modifier.width(5.dp))
        }
        Text(
            text = stringResource(status.labelRes()),
            style = MaterialTheme.typography.labelSmall,
            color = statusAccentColor(status).takeIf { it != color } ?: color,
        )
    }
}

@Composable
private fun statusAccentColor(status: ToolStatus): Color = when (status) {
    ToolStatus.PENDING -> Warning
    ToolStatus.RUNNING -> MaterialTheme.colorScheme.secondary
    ToolStatus.COMPLETED -> Success
    ToolStatus.ERROR -> Danger
}

@Composable
private fun statusContainerColor(status: ToolStatus): Color =
    statusAccentColor(status).copy(alpha = 0.18f)

@androidx.annotation.StringRes
private fun ToolStatus.labelRes(): Int = when (this) {
    ToolStatus.PENDING -> R.string.status_pending
    ToolStatus.RUNNING -> R.string.status_running
    ToolStatus.COMPLETED -> R.string.status_completed
    ToolStatus.ERROR -> R.string.status_error
}

// ---- Shell (monospace output + exit code) ----------------------------------

@Composable
fun ShellPartCard(part: MessagePart.ShellPart, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0A0E1A))
            .border(1.dp, Color(0xFF1D2547), RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Terminal,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = Success,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = part.command,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = Success,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            part.exitCode?.let { ExitBadge(it) }
        }
        if (part.output.isNotBlank()) {
            Text(
                text = part.output,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = Color(0xFFC7D0F0),
                modifier = Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .heightIn(max = 180.dp)
                    .verticalScroll(rememberScrollState()),
            )
            if (part.outputTruncated) {
                TruncatedChip()
            }
        }
    }
}

@Composable
private fun ExitBadge(code: Int) {
    val color = if (code == 0) Success else Danger
    Text(
        text = stringResource(R.string.exit_code, code),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .padding(start = 8.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.18f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

// ---- Step (progress indicator) ---------------------------------------------

@Composable
fun StepPartCard(part: MessagePart.StepPart, modifier: Modifier = Modifier) {
    val active = part.state == StepState.IN_PROGRESS || part.state == StepState.STARTED
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = when (part.state) {
                    StepState.COMPLETED -> Icons.Filled.CheckCircle
                    StepState.FAILED -> Icons.Filled.Error
                    else -> Icons.Filled.Timeline
                },
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = when (part.state) {
                    StepState.COMPLETED -> Success
                    StepState.FAILED -> Danger
                    else -> MaterialTheme.colorScheme.primary
                },
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = part.description.ifBlank { stringResource(R.string.part_step_default) },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 2,
            )
        }
        if (active) {
            Row(
                modifier = Modifier.padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(10.dp),
                    strokeWidth = 1.5.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(2.dp)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                            RoundedCornerShape(1.dp),
                        ),
                )
            }
        }
    }
}

// ---- Agent toggle (build vs plan) ------------------------------------------

@Composable
fun AgentToggle(
    selected: AgentMode,
    onSelect: (AgentMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        AgentMode.entries.forEach { mode ->
            val isSelected = mode == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                    )
                    .clickable { onSelect(mode) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(
                    text = mode.wireName,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

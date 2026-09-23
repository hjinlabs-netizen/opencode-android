package com.anomalyco.opencode.ui.files

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anomalyco.opencode.R
import com.anomalyco.opencode.domain.model.DiffLineKind
import com.anomalyco.opencode.domain.model.DiffStatus
import com.anomalyco.opencode.domain.model.FileDiff
import com.anomalyco.opencode.ui.theme.Danger
import com.anomalyco.opencode.ui.theme.Success
import com.anomalyco.opencode.ui.theme.Warning
import kotlinx.coroutines.launch

/**
 * One changed file inside the diff viewer: a header row (status icon, path,
 * +/- counters) that expands into color-coded unified-diff hunks.
 */
@Composable
fun DiffFileCard(
    diff: FileDiff,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    canCopy: Boolean = false,
    onCopied: () -> Unit = {},
) {
    val clipboard = LocalClipboardManager.current
    val copyScope = rememberCoroutineScope()
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Icon(
                    imageVector = when (diff.status) {
                        DiffStatus.ADDED -> Icons.Filled.Add
                        DiffStatus.DELETED -> Icons.Filled.Delete
                        else -> Icons.Filled.Edit
                    },
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = when (diff.status) {
                        DiffStatus.ADDED -> Success
                        DiffStatus.DELETED -> Danger
                        else -> MaterialTheme.colorScheme.secondary
                    },
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = diff.path.substringAfterLast('/').ifBlank { diff.path },
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "+${diff.additions}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Success,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "-${diff.deletions}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Danger,
                )
                if (canCopy) {
                    IconButton(
                        onClick = {
                            copyScope.launch { clipboard.setText(AnnotatedString(diff.toPatchText())) }
                            onCopied()
                        },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ContentCopy,
                            contentDescription = stringResource(R.string.diff_copy_patch),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) {
                        Icons.Filled.ExpandLess
                    } else {
                        Icons.Filled.ExpandMore
                    },
                    contentDescription = null,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 0.5.dp)
                    if (diff.truncated) {
                        // Sprint M.3: honest notice that the patch was cut at
                        // the memory limit BEFORE parsing - partial view.
                        Text(
                            text = stringResource(R.string.diff_truncated),
                            style = MaterialTheme.typography.labelSmall,
                            color = Warning,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                    diff.hunks.forEach { hunk ->
                        Text(
                            text = hunk.header,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                        hunk.lines.forEach { line -> DiffLineRow(line.text, line.kind) }
                    }
                }
            }
        }
    }
}

/**
 * Reconstructs a unified-diff patch for [this] file from its parsed hunks so
 * the copy-patch affordance yields pasteable, git-style content. The parsed
 * line text is stored without its leading marker, so the `+`/`-`/space prefix
 * is re-applied from each line's kind.
 */
internal fun FileDiff.toPatchText(): String = buildString {
    append("--- ").append(oldPath).append('\n')
    append("+++ ").append(newPath).append('\n')
    for (hunk in hunks) {
        append(hunk.header).append('\n')
        for (line in hunk.lines) {
            val prefix = when (line.kind) {
                DiffLineKind.ADDED -> "+"
                DiffLineKind.DELETED -> "-"
                DiffLineKind.CONTEXT -> " "
            }
            append(prefix).append(line.text).append('\n')
        }
    }
}

@Composable
private fun DiffLineRow(text: String, kind: DiffLineKind) {
    val (bg, glyph) = when (kind) {
        DiffLineKind.ADDED -> Success.copy(alpha = 0.14f) to "+"
        DiffLineKind.DELETED -> Danger.copy(alpha = 0.14f) to "-"
        DiffLineKind.CONTEXT -> Color.Transparent to " "
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .background(bg),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = glyph,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = when (kind) {
                DiffLineKind.ADDED -> Success
                DiffLineKind.DELETED -> Danger
                DiffLineKind.CONTEXT -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(start = 10.dp, top = 1.dp, bottom = 1.dp),
        )
        Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .clip(RoundedCornerShape(bottomEnd = 6.dp))
                .padding(end = 10.dp),
        )
    }
}

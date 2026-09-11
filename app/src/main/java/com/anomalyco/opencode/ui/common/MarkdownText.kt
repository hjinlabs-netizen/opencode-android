package com.anomalyco.opencode.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anomalyco.opencode.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Dependency-free Markdown subset renderer for assistant text parts.
 * Supports: `#`/`##`/`###` headings, `- `/`* ` bullets, ``` fenced code
 * blocks (with a language badge + copy button), inline **bold** and `code`.
 * Everything else renders as a plain paragraph. Deliberately small — a full
 * CommonMark parser is overkill for chat bubbles and a dedicated library can
 * replace it later.
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    codeBackground: Color = MaterialTheme.colorScheme.surfaceVariant,
    onCodeCopied: (String) -> Unit = {},
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        parseMarkdownBlocks(text).forEach { block ->
            when (block) {
                is MdBlock.Heading -> Text(
                    text = inlineSpan(block.text),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.labelLarge
                    }.mergeStyleBold(),
                )
                is MdBlock.Bullet -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("•", color = MaterialTheme.colorScheme.primary)
                    Text(text = inlineSpan(block.text), style = MaterialTheme.typography.bodyMedium)
                }
                is MdBlock.Code -> CodeBlock(block, codeBackground, onCodeCopied)
                is MdBlock.Paragraph -> Text(
                    text = inlineSpan(block.text),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun CodeBlock(
    block: MdBlock.Code,
    codeBackground: Color,
    onCodeCopied: (String) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val plainLabel = stringResource(R.string.code_language_plain)
    val languageLabel = block.language ?: plainLabel
    var copied by remember(block.code) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_600)
            copied = false
        }
    }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = codeBackground.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 4.dp, top = 2.dp),
            ) {
                Text(
                    text = languageLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        scope.launch { clipboard.setText(AnnotatedString(block.code)) }
                        copied = true
                        onCodeCopied(languageLabel)
                    },
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = stringResource(
                            if (copied) R.string.code_copied else R.string.code_copy,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
            Text(
                text = block.code,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(10.dp),
            )
        }
    }
}

private fun androidx.compose.ui.text.TextStyle.mergeStyleBold() =
    copy(fontWeight = FontWeight.SemiBold)

// ---- pure parsing (unit-testable) -----------------------------------------

sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Bullet(val text: String) : MdBlock
    data class Code(val code: String, val language: String? = null) : MdBlock
    data class Paragraph(val text: String) : MdBlock
}

internal fun parseMarkdownBlocks(source: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val paragraph = StringBuilder()
    val codeBuf = StringBuilder()
    var inFence = false
    var fenceLanguage: String? = null

    fun flushParagraph() {
        if (paragraph.isNotBlank()) blocks += MdBlock.Paragraph(paragraph.toString().trim())
        paragraph.setLength(0)
    }

    for (rawLine in source.lines()) {
        val line = rawLine.trimEnd()
        when {
            line.trimStart().startsWith("```") -> {
                if (inFence) {
                    blocks += MdBlock.Code(codeBuf.toString().trimEnd(), fenceLanguage)
                    codeBuf.setLength(0)
                    fenceLanguage = null
                    inFence = false
                } else {
                    flushParagraph()
                    // Info string after the opening fence = language id (first word).
                    fenceLanguage = line.trimStart()
                        .removePrefix("```")
                        .trim()
                        .split(' ', ',')
                        .firstOrNull()
                        ?.takeIf { it.isNotBlank() }
                    inFence = true
                }
            }
            inFence -> codeBuf.appendLine(rawLine)
            else -> {
                val heading = HEADING_REGEX.matchEntire(line.trimStart())
                val bullet = BULLET_REGEX.matchEntire(line.trimStart())
                when {
                    heading != null -> {
                        flushParagraph()
                        blocks += MdBlock.Heading(
                            level = heading.groupValues[1].length,
                            text = heading.groupValues[2],
                        )
                    }
                    bullet != null -> {
                        flushParagraph()
                        blocks += MdBlock.Bullet(bullet.groupValues[1])
                    }
                    line.isBlank() -> flushParagraph()
                    else -> {
                        if (paragraph.isNotEmpty()) paragraph.append('\n')
                        paragraph.append(line)
                    }
                }
            }
        }
    }
    if (inFence) blocks += MdBlock.Code(codeBuf.toString().trimEnd(), fenceLanguage)
    flushParagraph()
    return blocks
}

/** Inline markdown: `**bold**` and `` `code` `` spans. */
internal fun inlineSpan(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end >= 0) {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    append(text.substring(i + 2, end))
                    pop()
                    i = end + 2
                } else {
                    append(text[i]); i++
                }
            }
            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end >= 0) {
                    pushStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = Color(0x33FFFFFF),
                        ),
                    )
                    append(text.substring(i + 1, end))
                    pop()
                    i = end + 1
                } else {
                    append(text[i]); i++
                }
            }
            else -> {
                append(text[i]); i++
            }
        }
    }
}

private val HEADING_REGEX = Regex("^(#{1,3})\\s+(.*)$")
private val BULLET_REGEX = Regex("^[-*]\\s+(.*)$")

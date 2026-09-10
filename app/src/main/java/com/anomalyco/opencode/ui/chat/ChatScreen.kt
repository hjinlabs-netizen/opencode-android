package com.anomalyco.opencode.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Difference
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anomalyco.opencode.domain.model.ChatMessage
import com.anomalyco.opencode.domain.model.MessagePart
import com.anomalyco.opencode.domain.model.MessageRole
import com.anomalyco.opencode.domain.model.StreamStatus
import com.anomalyco.opencode.ui.common.formatRelativeTime
import com.anomalyco.opencode.ui.theme.Success
import com.anomalyco.opencode.ui.theme.Warning

/**
 * Chat room for a single session. Renders history + live-streamed assistant
 * bubbles (polymorphic part cards), auto-follows the token stream while the
 * user is at the bottom, and offers a prompt input with the build/plan agent
 * toggle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onBack: () -> Unit,
    onOpenFiles: () -> Unit = {},
    onOpenDiff: () -> Unit = {},
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onErrorShown()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = state.sessionTitle.ifBlank { "Oturum" },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                        )
                        // Tappable model chip → provider/model picker sheet.
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    if (state.isLoadingProviders) {
                                        viewModel.loadProviders(force = true)
                                    } else {
                                        viewModel.openModelPicker()
                                    }
                                },
                        ) {
                            Text(
                                text = state.currentModel?.displayName
                                    ?: if (state.isLoadingProviders) "Model yükleniyor…" else "Model seç",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                maxLines = 1,
                            )
                            Icon(
                                imageVector = Icons.Filled.ArrowDropDown,
                                contentDescription = "Modeli değiştir",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.tertiary,
                            )
                            Text(
                                text = "· ${state.streamStatus.label()}",
                                style = MaterialTheme.typography.labelSmall,
                                color = streamStatusColor(state.streamStatus),
                                maxLines = 1,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Geri",
                        )
                    }
                },
                actions = {
                    if (state.pendingInteractions.isNotEmpty() && !state.interactionVisible) {
                        Badge(
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .clickable { viewModel.showInteraction() },
                        ) {
                            Icon(
                                imageVector = Icons.Filled.NotificationsActive,
                                contentDescription = "Bekleyen istekler",
                            )
                        }
                    }
                    IconButton(onClick = onOpenFiles) {
                        Icon(Icons.Filled.Folder, contentDescription = "Dosyalar")
                    }
                    IconButton(onClick = onOpenDiff) {
                        Icon(Icons.Filled.Difference, contentDescription = "Değişiklikler")
                    }
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Yenile")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            ChatInputBar(
                input = state.input,
                agent = state.agent,
                isSending = state.isSending,
                isBusy = state.isBusy,
                onInputChange = viewModel::onInputChange,
                onAgentChange = viewModel::onAgentChange,
                onSend = viewModel::send,
            )
        },
    ) { innerPadding ->
        MessageList(
            state = state,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        )
    }

    // The agent is blocked on the front-most interaction until it is resolved.
    val front = state.pendingInteractions.firstOrNull()
    if (front != null && state.interactionVisible) {
        when (front) {
            is PendingInteraction.Permission -> PermissionDialog(
                request = front.request,
                onDecision = { decision ->
                    viewModel.respondToPermission(front.request.requestId, decision)
                },
                onDismiss = viewModel::hideInteraction,
            )
            is PendingInteraction.Question -> QuestionDialog(
                request = front.request,
                onAnswer = { answers ->
                    viewModel.respondToQuestion(front.request.questionId, answers)
                },
                onDismiss = viewModel::hideInteraction,
            )
        }
    }

    if (state.isModelPickerOpen) {
        ModelPickerSheet(
            providers = state.providers,
            isLoading = state.isLoadingProviders,
            switchingModelId = state.switchingModelId,
            onDismiss = viewModel::closeModelPicker,
            onSelect = viewModel::selectModel,
        )
    }
}

@Composable
private fun MessageList(
    state: ChatUiState,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val ordered = state.messages.asReversed() // reverseLayout => newest first

    // A signature that changes on every token append so we can auto-follow.
    val tailSignature = ordered.firstOrNull()?.parts?.sumOf { part ->
        when (part) {
            is MessagePart.TextPart -> part.content.length
            is MessagePart.ReasoningPart -> part.thinking.length
            else -> 0
        }
    } ?: 0
    val atBottom = !listState.canScrollBackward
    LaunchedEffect(ordered.size, tailSignature) {
        if (atBottom && ordered.isNotEmpty()) listState.animateScrollToItem(0)
    }

    when {
        state.isLoadingHistory && state.messages.isEmpty() -> Box(
            modifier = modifier,
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator() }

        else -> LazyColumn(
            modifier = modifier,
            state = listState,
            reverseLayout = true,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(items = ordered, key = { it.id }) { message ->
                MessageBubble(
                    message = message,
                    isLive = message.id ==
                        MessageAssembler.liveMessageId(state.sessionId) && state.isBusy,
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, isLive: Boolean) {
    val isUser = message.role == MessageRole.USER
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = if (isUser) 18.dp else 6.dp,
                topEnd = if (isUser) 6.dp else 18.dp,
                bottomStart = 18.dp,
                bottomEnd = 18.dp,
            ),
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            tonalElevation = if (isUser) 0.dp else 1.dp,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (message.parts.isEmpty() && isLive) {
                    TypingDots()
                } else {
                    message.parts.forEach { part ->
                        MessagePartCard(part)
                    }
                }
            }
        }
        Spacer(Modifier.size(3.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 6.dp),
        ) {
            Text(
                text = if (isUser) "Sen" else "Asistan",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (message.createdAt > 0L) {
                Text(
                    text = " · ${formatRelativeTime(message.createdAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TypingDots() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 2.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "Yanıtlanıyor…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ChatInputBar(
    input: String,
    agent: AgentMode,
    isSending: Boolean,
    isBusy: Boolean,
    onInputChange: (String) -> Unit,
    onAgentChange: (AgentMode) -> Unit,
    onSend: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Column(modifier = Modifier.imePadding()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(start = 14.dp, top = 8.dp),
            ) {
                AgentToggle(selected = agent, onSelect = onAgentChange)
                if (isBusy && !isSending) {
                    Text(
                        text = "Model çalışıyor…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = onInputChange,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(max = 140.dp),
                    placeholder = { Text("Mesaj yazın…") },
                    shape = RoundedCornerShape(22.dp),
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                )
                FilledIconButton(
                    onClick = onSend,
                    enabled = input.isNotBlank() && !isSending,
                    modifier = Modifier.size(48.dp),
                ) {
                    if (isSending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Gönder",
                        )
                    }
                }
            }
        }
    }
}

private fun StreamStatus.label(): String = when (this) {
    StreamStatus.Disconnected -> "Bağlantı yok"
    StreamStatus.Connecting -> "Bağlanıyor…"
    StreamStatus.Connected -> "Canlı"
    is StreamStatus.Error -> "Koptu — yeniden bağlanılıyor"
}

@Composable
private fun streamStatusColor(status: StreamStatus): Color = when (status) {
    StreamStatus.Disconnected -> MaterialTheme.colorScheme.onSurfaceVariant
    StreamStatus.Connecting -> Warning
    StreamStatus.Connected -> Success
    is StreamStatus.Error -> MaterialTheme.colorScheme.error
}

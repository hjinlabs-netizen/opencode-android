package com.anomalyco.opencode.ui.chat

import androidx.annotation.StringRes
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
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anomalyco.opencode.R
import com.anomalyco.opencode.domain.model.ChatMessage
import com.anomalyco.opencode.domain.model.MessagePart
import com.anomalyco.opencode.domain.model.MessageRole
import com.anomalyco.opencode.domain.model.StreamStatus
import com.anomalyco.opencode.ui.common.relativeTimeText
import com.anomalyco.opencode.ui.theme.Success
import com.anomalyco.opencode.ui.theme.Warning
import kotlinx.coroutines.launch

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
    onOpenFiles: (String) -> Unit = {},
    onOpenDiff: () -> Unit = {},
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val uiScope = rememberCoroutineScope()
    val context = LocalContext.current
    val onCodeCopied: (String) -> Unit = {
        val message = context.getString(R.string.code_copied)
        uiScope.launch { snackbarHostState.showSnackbar(message) }
    }

    // Re-entering the screen (or returning from background) mid-turn: reconcile
    // any deltas missed while unsubscribed (P0-5).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onResume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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
                            text = state.sessionTitle.ifBlank { stringResource(R.string.chat_session_fallback) },
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
                                text = state.currentModel?.displayName ?: stringResource(
                                    if (state.isLoadingProviders) R.string.chat_model_loading else R.string.chat_model_select,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                maxLines = 1,
                            )
                            Icon(
                                imageVector = Icons.Filled.ArrowDropDown,
                                contentDescription = stringResource(R.string.chat_model_select),
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.tertiary,
                            )
                            Text(
                                text = "· ${stringResource(state.streamStatus.labelRes())}",
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
                            contentDescription = stringResource(R.string.action_back),
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
                                contentDescription = stringResource(R.string.chat_pending_requests),
                            )
                        }
                    }
                    IconButton(onClick = { onOpenFiles(state.directory) }) {
                        Icon(Icons.Filled.Folder, contentDescription = stringResource(R.string.chat_files))
                    }
                    IconButton(onClick = onOpenDiff) {
                        Icon(Icons.Filled.Difference, contentDescription = stringResource(R.string.chat_changes))
                    }
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_refresh))
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
                canRetry = state.lastPrompt != null && !state.isBusy && !state.isSending,
                onInputChange = viewModel::onInputChange,
                onAgentChange = viewModel::onAgentChange,
                onSend = viewModel::send,
                onAbort = viewModel::abort,
                onRetry = viewModel::retry,
            )
        },
    ) { innerPadding ->
        MessageList(
            state = state,
            onCodeCopied = onCodeCopied,
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
    onCodeCopied: (String) -> Unit,
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
                    onCodeCopied = onCodeCopied,
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    isLive: Boolean,
    onCodeCopied: (String) -> Unit,
) {
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
                        MessagePartCard(part, onCodeCopied = onCodeCopied)
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
                text = stringResource(if (isUser) R.string.chat_role_you else R.string.chat_role_assistant),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (message.createdAt > 0L) {
                Text(
                    text = " · ${relativeTimeText(message.createdAt)}",
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
            text = stringResource(R.string.chat_typing),
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
    canRetry: Boolean,
    onInputChange: (String) -> Unit,
    onAgentChange: (AgentMode) -> Unit,
    onSend: () -> Unit,
    onAbort: () -> Unit,
    onRetry: () -> Unit,
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
                        text = stringResource(R.string.chat_model_working),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (canRetry) {
                    TextButton(onClick = onRetry) {
                        Icon(
                            imageVector = Icons.Filled.Replay,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = stringResource(R.string.chat_retry),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
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
                    placeholder = { Text(stringResource(R.string.chat_input_hint)) },
                    shape = RoundedCornerShape(22.dp),
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                )
                if (isBusy) {
                    // Turn controls (Sprint C): stop the running turn.
                    FilledIconButton(
                        onClick = onAbort,
                        modifier = Modifier.size(48.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    ) {
                        if (isSending) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Stop,
                                contentDescription = stringResource(R.string.chat_abort),
                            )
                        }
                    }
                } else {
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
                                contentDescription = stringResource(R.string.action_send),
                            )
                        }
                    }
                }
            }
        }
    }
}

@StringRes
private fun StreamStatus.labelRes(): Int = when (this) {
    StreamStatus.Disconnected -> R.string.stream_disconnected
    StreamStatus.Connecting -> R.string.stream_connecting
    StreamStatus.Connected -> R.string.stream_connected
    is StreamStatus.Error -> R.string.stream_error
}

@Composable
private fun streamStatusColor(status: StreamStatus): Color = when (status) {
    StreamStatus.Disconnected -> MaterialTheme.colorScheme.onSurfaceVariant
    StreamStatus.Connecting -> Warning
    StreamStatus.Connected -> Success
    is StreamStatus.Error -> MaterialTheme.colorScheme.error
}

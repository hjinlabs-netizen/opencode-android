package com.anomalyco.opencode.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anomalyco.opencode.domain.model.ChatMessage
import com.anomalyco.opencode.domain.model.MessagePart
import com.anomalyco.opencode.domain.model.MessageRole
import com.anomalyco.opencode.domain.model.ModelInfo
import com.anomalyco.opencode.domain.model.PermissionDecision
import com.anomalyco.opencode.domain.model.PermissionRequest
import com.anomalyco.opencode.domain.model.ProviderConfig
import com.anomalyco.opencode.domain.model.QuestionRequest
import com.anomalyco.opencode.domain.model.StreamEvent
import com.anomalyco.opencode.domain.model.StreamStatus
import com.anomalyco.opencode.domain.repository.ChatStreamRepository
import com.anomalyco.opencode.domain.repository.InteractionRepository
import com.anomalyco.opencode.domain.repository.ModelRepository
import com.anomalyco.opencode.domain.repository.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The two driving agents the OpenCode server ships with. */
enum class AgentMode(val wireName: String) {
    BUILD("build"),
    PLAN("plan"),
    ;

    companion object {
        fun fromWire(value: String?): AgentMode =
            entries.firstOrNull { it.wireName == value } ?: BUILD
    }
}

/** A server-initiated user interaction awaiting a response, ready for the UI. */
sealed interface PendingInteraction {
    val id: String

    data class Permission(val request: PermissionRequest) : PendingInteraction {
        override val id: String get() = request.requestId
    }

    data class Question(val request: QuestionRequest) : PendingInteraction {
        override val id: String get() = request.questionId
    }
}

/** Immutable render state for [ChatScreen]. */
data class ChatUiState(
    val sessionId: String = "",
    val sessionTitle: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val input: String = "",
    val agent: AgentMode = AgentMode.BUILD,
    val isLoadingHistory: Boolean = true,
    /** A prompt request is in flight. */
    val isSending: Boolean = false,
    /** The server is actively producing output (stream events pending idle). */
    val isBusy: Boolean = false,
    val streamStatus: StreamStatus = StreamStatus.Disconnected,
    /** Permissions/questions blocking the agent, oldest first. */
    val pendingInteractions: List<PendingInteraction> = emptyList(),
    /** Whether the front-most interaction is shown as a dialog. */
    val interactionVisible: Boolean = true,
    /** --- model & agent picker --- */
    val providers: List<ProviderConfig> = emptyList(),
    val currentModel: ModelInfo? = null,
    val isLoadingProviders: Boolean = false,
    val isModelPickerOpen: Boolean = false,
    val switchingModelId: String? = null,
    val error: String? = null,
)
/**
 * Owns one chat room: initial transcript, live stream stitching (via
 * [MessageAssembler]) and prompt sending. Events for other sessions are
 * filtered out; a history refresh keeps any in-flight live bubble.
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sessionRepository: SessionRepository,
    private val chatStreamRepository: ChatStreamRepository,
    private val interactionRepository: InteractionRepository,
    private val modelRepository: ModelRepository,
) : ViewModel() {

    private val sessionId: String = savedStateHandle[ARG_SESSION_ID] ?: ""
    private var liveTurnCounter = 0

    private val _uiState = MutableStateFlow(ChatUiState(sessionId = sessionId))
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            // Resilience (Phase 4): when a dropped stream reconnects and goes
            // healthy again, events missed during the outage are gone for good
            // (the feed is replay=0). Re-pull the transcript so the visible
            // state converges on the server truth instead of freezing with
            // half-finished bubbles. The in-flight live bubble is preserved by
            // refresh(); completed turns are replaced wholesale.
            var previous: StreamStatus = _uiState.value.streamStatus
            chatStreamRepository.status.collect { s ->
                if (previous is StreamStatus.Error && s == StreamStatus.Connected) {
                    refresh()
                }
                previous = s
                _uiState.update { it.copy(streamStatus = s) }
            }
        }
        viewModelScope.launch {
            chatStreamRepository.events.collect { event ->
                // Defensive: a decoding/mapping bug in a single malformed event
                // must never cancel this collector, or the transcript would
                // silently freeze while the SSE connection reports Connected.
                runCatching { onStreamEvent(event) }
            }
        }
    }

    /** (Re)loads the message transcript and session metadata from the server. */
    fun refresh() {
        viewModelScope.launch {
            sessionRepository.getSession(sessionId).onSuccess { session ->
                _uiState.update { it.copy(sessionTitle = session.title) }
            }
            sessionRepository.loadMessages(sessionId)
                .onSuccess { history ->
                    _uiState.update { current ->
                        val live = current.messages
                            .firstOrNull { it.id == MessageAssembler.liveMessageId(sessionId) }
                        val merged = history + listOfNotNull(live)
                        when {
                            // Resilience: a transient/EMPTY history during an
                            // active turn or a reconnect resync must NEVER
                            // blank (or partially erase) the visible
                            // transcript. Keep what the user already sees
                            // until real rows arrive.
                            history.isEmpty() && current.messages.isNotEmpty() ->
                                current.copy(isLoadingHistory = false)
                            else ->
                                current.copy(messages = merged, isLoadingHistory = false)
                        }
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(isLoadingHistory = false, error = error.message)
                    }
                }
        }
    }

    fun onInputChange(value: String) = _uiState.update { it.copy(input = value) }

    fun onAgentChange(mode: AgentMode) = _uiState.update { it.copy(agent = mode) }

    fun onErrorShown() = _uiState.update { it.copy(error = null) }

    // ---- model & agent picker ------------------------------------------------

    /** Fetches the provider catalog (idempotent unless [force]); feeds the top-bar chip. */
    fun loadProviders(force: Boolean = false) {
        if (!force && _uiState.value.providers.isNotEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingProviders = true) }
            modelRepository.fetchProviders()
                .onSuccess { providers ->
                    _uiState.update { current ->
                        current.copy(
                            providers = providers,
                            currentModel = providers
                                .flatMap { it.models }
                                .firstOrNull { it.isCurrent },
                            isLoadingProviders = false,
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(isLoadingProviders = false, error = error.message)
                    }
                }
        }
    }

    fun openModelPicker() {
        _uiState.update { it.copy(isModelPickerOpen = true) }
        loadProviders()
    }

    fun closeModelPicker() = _uiState.update { it.copy(isModelPickerOpen = false) }

    /** Switches the active model and re-flags the catalog on success. */
    fun selectModel(model: ModelInfo) {
        if (_uiState.value.switchingModelId != null) return
        _uiState.update { it.copy(switchingModelId = model.qualifiedId) }
        viewModelScope.launch {
            modelRepository.setActiveModel(model.providerId, model.modelId)
                .onSuccess {
                    _uiState.update { current ->
                        current.copy(
                            switchingModelId = null,
                            isModelPickerOpen = false,
                            currentModel = model,
                            providers = current.providers.map { provider ->
                                provider.copy(
                                    models = provider.models.map {
                                        it.copy(isCurrent = it.qualifiedId == model.qualifiedId)
                                    },
                                )
                            },
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(switchingModelId = null, error = error.message) }
                }
        }
    }

    /** Send the current input as a user prompt (optimistic append). */
    fun send() {
        val state = _uiState.value
        val text = state.input.trim()
        if (text.isEmpty() || state.isSending) return

        val optimisticId = "local-user-${System.nanoTime()}"
        val userMessage = ChatMessage(
            id = optimisticId,
            sessionId = sessionId,
            role = MessageRole.USER,
            parts = listOf(MessagePart.TextPart(text)),
            createdAt = System.currentTimeMillis(),
        )
        _uiState.update { current ->
            current.copy(
                // Close the previous live bubble so this turn starts fresh.
                messages = MessageAssembler.rotateLiveMessage(
                    messages = current.messages + userMessage,
                    sessionId = sessionId,
                    newId = "assistant-$sessionId-${++liveTurnCounter}",
                ),
                input = "",
                isSending = true,
                isBusy = true,
            )
        }
        viewModelScope.launch {
            val outcome =
                sessionRepository.sendPrompt(sessionId, text, agent = state.agent.wireName)
            outcome
                .onSuccess { serverMessage ->
                    _uiState.update { current ->
                        current.copy(
                            // Swap in the server row only when it carries an
                            // id; an unrecognised response shape keeps the
                            // optimistic bubble visible until history sync.
                            messages = current.messages.map {
                                if (it.id == optimisticId && serverMessage.id.isNotBlank()) {
                                    serverMessage
                                } else {
                                    it
                                }
                            },
                            isSending = false,
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update { current ->
                        current.copy(
                            // The user's message stays visible even when the
                            // POST fails: OpenCode's send-message call resolves
                            // at END OF TURN, so a late error/timeout almost
                            // never means the prompt was rejected — wiping the
                            // bubble blanks the whole transcript mid-stream.
                            // The snackbar explains; a refresh reconciles.
                            isSending = false,
                            error = error.message,
                        )
                    }
                }
            // The long poll resolving means the turn is over server-side, no
            // matter the outcome: end the busy state and reconcile the
            // transcript with the authoritative history. The live bubble is
            // dropped (its content is now persisted) unless a new turn has
            // already begun streaming.
            _uiState.update { it.copy(isBusy = false) }
            syncTranscriptFromServer()
        }
    }

    private suspend fun syncTranscriptFromServer() {
        sessionRepository.loadMessages(sessionId).onSuccess { history ->
            if (history.isEmpty()) return@onSuccess // never blank a live transcript
            _uiState.update { current ->
                val live =
                    if (current.isBusy) {
                        current.messages.firstOrNull {
                            it.id == MessageAssembler.liveMessageId(sessionId)
                        }
                    } else {
                        null
                    }
                current.copy(messages = history + listOfNotNull(live))
            }
        }
    }

    private fun onStreamEvent(event: StreamEvent) {
        val target = sessionIdOf(event) ?: return
        // SessionError may be global (null id) or addressed to this session.
        if (target.isNotEmpty() && target != sessionId) return

        // New permission/question requests join the pending queue (deduped by
        // id) and pop open the front-most one.
        val incoming = when (event) {
            is StreamEvent.PermissionAsked ->
                if (_uiState.value.pendingInteractions.any { it.id == event.request.requestId }) {
                    null
                } else {
                    PendingInteraction.Permission(event.request)
                }
            is StreamEvent.QuestionAsked ->
                if (_uiState.value.pendingInteractions.any { it.id == event.request.questionId }) {
                    null
                } else {
                    PendingInteraction.Question(event.request)
                }
            else -> null
        }

        _uiState.update { current ->
            val settled = event is StreamEvent.SessionIdle
            current.copy(
                messages = MessageAssembler.apply(current.messages, sessionId, event),
                isBusy = when (event) {
                    is StreamEvent.SessionIdle, is StreamEvent.SessionError -> false
                    else -> true
                },
                pendingInteractions = when {
                    settled -> emptyList()
                    incoming != null -> current.pendingInteractions + incoming
                    else -> current.pendingInteractions
                },
                interactionVisible = if (incoming != null) true else current.interactionVisible,
                error = (event as? StreamEvent.SessionError)?.message ?: current.error,
            )
        }
    }

    /** Resolve a permission prompt; the card clears optimistically and the next queued one surfaces. */
    fun respondToPermission(requestId: String, decision: PermissionDecision) {
        _uiState.update { current ->
            current.copy(pendingInteractions = current.pendingInteractions.filterNot { it.id == requestId })
        }
        viewModelScope.launch {
            interactionRepository.respondPermission(requestId, decision)
                .onFailure { error -> _uiState.update { it.copy(error = error.message) } }
        }
    }

    /** Answer a question with the selected/free-text labels. */
    fun respondToQuestion(questionId: String, answers: List<String>) {
        _uiState.update { current ->
            current.copy(pendingInteractions = current.pendingInteractions.filterNot { it.id == questionId })
        }
        viewModelScope.launch {
            interactionRepository.respondQuestion(questionId, answers)
                .onFailure { error -> _uiState.update { it.copy(error = error.message) } }
        }
    }

    /** Dismiss the current interaction dialog without answering (it stays queued). */
    fun hideInteraction() = _uiState.update { it.copy(interactionVisible = false) }

    /** Re-show the queued interaction dialog (badge on the top bar). */
    fun showInteraction() = _uiState.update { it.copy(interactionVisible = true) }

    /** Session id carried by an event, or null for events we ignore entirely. */
    private fun sessionIdOf(event: StreamEvent): String? = when (event) {
        is StreamEvent.TextDelta -> event.sessionId
        is StreamEvent.ReasoningDelta -> event.sessionId
        is StreamEvent.ToolCalled -> event.sessionId
        is StreamEvent.ToolUpdated -> event.sessionId
        is StreamEvent.ToolFinished -> event.sessionId
        is StreamEvent.StepStarted -> event.sessionId
        is StreamEvent.StepFinished -> event.sessionId
        is StreamEvent.MessageUpdated -> event.sessionId
        is StreamEvent.SessionIdle -> event.sessionId
        is StreamEvent.SessionError -> event.sessionId ?: ""
        is StreamEvent.PermissionAsked -> event.sessionId
        is StreamEvent.QuestionAsked -> event.sessionId
        is StreamEvent.Unknown -> null
    }

    companion object {
        const val ARG_SESSION_ID = "sessionId"
    }
}

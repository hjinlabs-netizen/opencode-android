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
import kotlinx.coroutines.Job
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
    /** Server-side working directory of this session (file explorer root). */
    val directory: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val input: String = "",
    val agent: AgentMode = AgentMode.BUILD,
    val isLoadingHistory: Boolean = true,
    /**
     * Prompt dispatch is unacknowledged. Clears at the FIRST proof the server
     * accepted the turn (any stream event) or when the end-of-turn long poll
     * resolves — never while the agent is merely still working.
     */
    val isSending: Boolean = false,
    /** The agent is actively thinking/streaming; settled by step-finish, idle, or turn resolution. */
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
    /** Text of the most recent prompt; enables the retry affordance (Sprint C). */
    val lastPrompt: String? = null,
    val error: String? = null,
)
/**
 * Owns one chat room: initial transcript, live stream stitching (via
 * [MessageAssembler]) and prompt sending. Events for other sessions are
 * filtered out; a history refresh keeps any in-flight live bubble.
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val sessionRepository: SessionRepository,
    private val chatStreamRepository: ChatStreamRepository,
    private val interactionRepository: InteractionRepository,
    private val modelRepository: ModelRepository,
) : ViewModel() {

    private val sessionId: String = savedStateHandle[ARG_SESSION_ID] ?: ""
    private var liveTurnCounter = 0

    /**
     * Monotonic turn sequence. Every [send] claims a token; only the token
     * still owned by the NEWEST turn may settle busy state or overwrite the
     * transcript — a late-resolving older long-poll POST can never clobber a
     * newer turn's live bubble (P0-3).
     */
    private var turnToken = 0L

    /** The in-flight end-of-turn `sendPrompt` job; cancelled by [abort] for instant release. */
    private var sendJob: Job? = null

    private val _uiState = MutableStateFlow(ChatUiState(sessionId = sessionId))
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        // Cold start: show the persisted model immediately (before the catalog
        // loads) so the top-bar chip is never blank after a process restart.
        modelRepository.preferredSelection()?.let { pref ->
            _uiState.update {
                it.copy(
                    currentModel = ModelInfo(
                        providerId = pref.providerId,
                        modelId = pref.modelId,
                        displayName = pref.modelId,
                        isCurrent = true,
                    ),
                )
            }
            // Auto-reconcile: pull the catalog in the background so the
            // persisted choice is validated and re-applied to `/config`
            // without the user having to open the picker sheet first.
            loadProviders()
        }
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
        // File chosen in the explorer ("Sohbete Ekle") arrives as a nav result.
        viewModelScope.launch {
            savedStateHandle.getStateFlow<String?>(PICKED_FILE_KEY, null).collect { path ->
                if (!path.isNullOrBlank()) {
                    _uiState.update { it.copy(input = appendMention(it.input, path)) }
                    savedStateHandle[PICKED_FILE_KEY] = null
                }
            }
        }
    }

    /** (Re)loads the message transcript and session metadata from the server. */
    fun refresh() {
        viewModelScope.launch {
            sessionRepository.getSession(sessionId).onSuccess { session ->
                _uiState.update {
                    it.copy(sessionTitle = session.title, directory = session.directory.orEmpty())
                }
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
                    val serverCurrent = providers
                        .flatMap { it.models }
                        .firstOrNull { it.isCurrent }
                    // Server reported no active model: restore the persisted
                    // selection (if it exists in this catalog) and re-apply it
                    // to `/config` so the agent uses it.
                    val restored = serverCurrent ?: run {
                        val pref = modelRepository.preferredSelection()
                        pref?.let { p ->
                            providers.flatMap { it.models }
                                .firstOrNull { it.providerId == p.providerId && it.modelId == p.modelId }
                        }
                    }
                    val effective = restored?.copy(isCurrent = true)
                    _uiState.update { current ->
                        current.copy(
                            providers = if (restored != null && serverCurrent == null) {
                                providers.withCurrent(restored.qualifiedId)
                            } else {
                                providers
                            },
                            currentModel = effective ?: current.currentModel?.let { seed ->
                                providers.flatMap { it.models }
                                    .firstOrNull { it.qualifiedId == seed.qualifiedId }
                            },
                            isLoadingProviders = false,
                        )
                    }
                    if (serverCurrent == null && restored != null) {
                        modelRepository.setActiveModel(restored.providerId, restored.modelId)
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(isLoadingProviders = false, error = error.message)
                    }
                }
        }
    }

    private fun List<ProviderConfig>.withCurrent(qualifiedId: String) = map { provider ->
        provider.copy(
            models = provider.models.map { it.copy(isCurrent = it.qualifiedId == qualifiedId) },
        )
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
        val myTurn = ++turnToken
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
                lastPrompt = text,
            )
        }
        sendJob = viewModelScope.launch {
            val outcome =
                sessionRepository.sendPrompt(sessionId, text, agent = state.agent.wireName)
            // The optimistic row may always be swapped for its server twin —
            // it is keyed by this send's own id.
            outcome.onSuccess { serverMessage ->
                _uiState.update { current ->
                    current.copy(
                        messages = current.messages.map {
                            if (it.id == optimisticId && serverMessage.id.isNotBlank()) {
                                serverMessage
                            } else {
                                it
                            }
                        },
                    )
                }
            }
            // Everything that touches SHARED turn state is token-guarded: a
            // late resolution of an older POST must not clear the newer turn's
            // isSending/isBusy flags nor sync away its live bubble (P0-3).
            if (myTurn != turnToken) return@launch
            outcome
                .onSuccess {
                    _uiState.update { it.copy(isSending = false) }
                }
                .onFailure { error ->
                    // The user's message stays visible even when the POST
                    // fails: OpenCode's send-message call resolves at END OF
                    // TURN, so a late error almost never means the prompt was
                    // rejected — wiping the bubble blanks the transcript.
                    _uiState.update { it.copy(isSending = false, error = error.message) }
                }
            // The long poll resolving means the turn is over server-side, no
            // matter the outcome: end the busy state and reconcile with the
            // authoritative history.
            _uiState.update { it.copy(isBusy = false) }
            syncTranscriptFromServer(myTurn)
        }
    }

    /**
     * Abort the running turn with INSTANT client-side release (the reported
     * bug was the button doing nothing while the agent kept generating):
     *  1. bump the turn token so the abandoned long-poll can never re-arm busy
     *     or overwrite the reconciled transcript,
     *  2. cancel the in-flight `sendPrompt` job (this also frees the server),
     *  3. settle `isBusy`/`isSending` synchronously — the UI frees immediately,
     *     without waiting on any network I/O,
     *  4. fire `POST /session/{id}/abort` in the background and reconcile.
     */
    fun abort() {
        if (!_uiState.value.isBusy && !_uiState.value.isSending) return
        val token = ++turnToken
        sendJob?.cancel()
        sendJob = null
        _uiState.update { it.copy(isBusy = false, isSending = false) }
        viewModelScope.launch {
            sessionRepository.abortSession(sessionId)
                .onFailure { error -> _uiState.update { it.copy(error = error.message) } }
            syncTranscriptFromServer(token)
        }
    }

    /** Re-dispatch the most recent prompt as a fresh turn. */
    fun retry() {
        val prompt = _uiState.value.lastPrompt ?: return
        if (_uiState.value.isBusy || _uiState.value.isSending) return
        _uiState.update { it.copy(input = prompt) }
        send()
    }

    /**
     * Screen resumed (nav back-stack or app foreground): if a turn was live
     * while the chat was not visible, deltas may have been missed by other
     * subscribers or a dropped connection — reconcile in the background (P0-5).
     */
    fun onResume() {
        if (_uiState.value.isBusy) refresh()
    }

    private suspend fun syncTranscriptFromServer(token: Long) {
        sessionRepository.loadMessages(sessionId).onSuccess { history ->
            if (history.isEmpty()) return@onSuccess // never blank a live transcript
            if (token != turnToken) return@onSuccess // a newer turn took over
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
                // Busy tracks agent PROGRESS, not the HTTP call. Re-arm only
                // on output-producing events so a trailing metadata event can
                // never re-stick the indicator after the turn ends.
                isBusy = when (event) {
                    is StreamEvent.SessionIdle,
                    is StreamEvent.SessionError,
                    is StreamEvent.StepFinished,
                    -> false
                    is StreamEvent.TextDelta,
                    is StreamEvent.ReasoningDelta,
                    is StreamEvent.ToolCalled,
                    is StreamEvent.ToolUpdated,
                    is StreamEvent.StepStarted,
                    is StreamEvent.PermissionAsked,
                    is StreamEvent.QuestionAsked,
                    -> true
                    is StreamEvent.MessageUpdated,
                    is StreamEvent.ToolFinished,
                    is StreamEvent.Unknown,
                    -> current.isBusy
                },
                // Any session-matched event proves the prompt was ACCEPTED by
                // the server: the send button unlocks immediately instead of
                // spinning until the end-of-turn long poll resolves.
                isSending = false,
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

        /** SavedStateHandle key the file explorer writes as its navigation result. */
        const val PICKED_FILE_KEY = "pickedFile"

        /**
         * Appends an `@path` context mention to the prompt, keeping any text
         * already typed and guaranteeing a trailing space for continued input.
         */
        internal fun appendMention(current: String, path: String): String {
            val base = current.trimEnd()
            return if (base.isEmpty()) "@$path " else "$base @$path "
        }
    }
}

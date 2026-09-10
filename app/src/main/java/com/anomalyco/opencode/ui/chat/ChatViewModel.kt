package com.anomalyco.opencode.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anomalyco.opencode.domain.model.ChatMessage
import com.anomalyco.opencode.domain.model.MessagePart
import com.anomalyco.opencode.domain.model.MessageRole
import com.anomalyco.opencode.domain.model.StreamEvent
import com.anomalyco.opencode.domain.model.StreamStatus
import com.anomalyco.opencode.domain.repository.ChatStreamRepository
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
) : ViewModel() {

    private val sessionId: String = savedStateHandle[ARG_SESSION_ID] ?: ""
    private var liveTurnCounter = 0

    private val _uiState = MutableStateFlow(ChatUiState(sessionId = sessionId))
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            chatStreamRepository.status.collect { s ->
                _uiState.update { it.copy(streamStatus = s) }
            }
        }
        viewModelScope.launch {
            chatStreamRepository.events.collect { event -> onStreamEvent(event) }
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
                        current.copy(
                            messages = history + listOfNotNull(live),
                            isLoadingHistory = false,
                        )
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
            sessionRepository.sendPrompt(sessionId, text, agent = state.agent.wireName)
                .onSuccess { serverMessage ->
                    _uiState.update { current ->
                        current.copy(
                            messages = current.messages.map {
                                if (it.id == optimisticId) serverMessage else it
                            },
                            isSending = false,
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update { current ->
                        current.copy(
                            // Roll the optimistic bubble back; nothing was sent.
                            messages = current.messages.filterNot { it.id == optimisticId },
                            isSending = false,
                            isBusy = false,
                            error = error.message,
                        )
                    }
                }
        }
    }

    private fun onStreamEvent(event: StreamEvent) {
        val target = sessionIdOf(event) ?: return
        // SessionError may be global (null id) or addressed to this session.
        if (target.isNotEmpty() && target != sessionId) return

        _uiState.update { current ->
            current.copy(
                messages = MessageAssembler.apply(current.messages, sessionId, event),
                isBusy = when (event) {
                    is StreamEvent.SessionIdle, is StreamEvent.SessionError -> false
                    else -> true
                },
                error = (event as? StreamEvent.SessionError)?.message ?: current.error,
            )
        }
    }

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
        is StreamEvent.Unknown -> null
    }

    companion object {
        const val ARG_SESSION_ID = "sessionId"
    }
}

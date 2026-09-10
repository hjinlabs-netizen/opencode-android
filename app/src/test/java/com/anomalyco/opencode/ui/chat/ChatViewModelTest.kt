package com.anomalyco.opencode.ui.chat

import com.anomalyco.opencode.domain.model.ChatMessage
import com.anomalyco.opencode.domain.model.MessagePart
import com.anomalyco.opencode.domain.model.MessageRole
import com.anomalyco.opencode.domain.model.Session
import com.anomalyco.opencode.domain.model.SessionSummary
import com.anomalyco.opencode.domain.model.StreamEvent
import com.anomalyco.opencode.domain.model.StreamStatus
import com.anomalyco.opencode.domain.repository.ChatStreamRepository
import com.anomalyco.opencode.domain.repository.SessionRepository
import com.anomalyco.opencode.util.MainDispatcherRule
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Test double with programmable returns and scripted stream events. */
private class FakeSessionRepository : SessionRepository {
    override val sessions: Flow<List<SessionSummary>> = MutableStateFlow(emptyList())

    var history: List<ChatMessage> = emptyList()
    var session: Session = Session(id = "s1", title = "Ses")
    var sendResult: (String) -> Result<ChatMessage> = {
        Result.success(ChatMessage(id = "server-user", sessionId = "s1", role = MessageRole.USER))
    }
    var lastPrompt: Pair<String, String?>? = null

    /** When set, sendPrompt suspends until this gate completes (tests the optimistic state). */
    var sendGate: CompletableDeferred<Unit>? = null

    override suspend fun refreshSessions(): Result<List<SessionSummary>> = Result.success(emptyList())
    override suspend fun createSession(agent: String?, title: String?) = Result.success(session)
    override suspend fun getSession(sessionId: String) = Result.success(session)
    override suspend fun loadMessages(sessionId: String) = Result.success(history)
    override suspend fun sendPrompt(sessionId: String, text: String, agent: String?): Result<ChatMessage> {
        lastPrompt = text to agent
        sendGate?.await()
        return sendResult(text)
    }
}

private class FakeStreamRepository : ChatStreamRepository {
    private val _events = MutableSharedFlow<StreamEvent>(extraBufferCapacity = 16)
    override val events: Flow<StreamEvent> = _events
    val statusFlow = MutableStateFlow<StreamStatus>(StreamStatus.Connected)
    override val status: Flow<StreamStatus> = statusFlow
    var reconnects = 0
    override suspend fun reconnect() { reconnects++ }
    fun push(event: StreamEvent) { _events.tryEmit(event) }
}

class ChatViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun build(history: List<ChatMessage> = emptyList()): Triple<ChatViewModel, FakeSessionRepository, FakeStreamRepository> {
        val sessions = FakeSessionRepository().apply { this.history = history }
        val stream = FakeStreamRepository()
        val vm = ChatViewModel(SavedStateHandle(mapOf("sessionId" to "s1")), sessions, stream)
        return Triple(vm, sessions, stream)
    }

    private fun assistant(id: String) = ChatMessage(
        id = id,
        sessionId = "s1",
        role = MessageRole.ASSISTANT,
        parts = listOf(MessagePart.TextPart("prior", id = "p0")),
    )

    @Test
    fun `loads session history and title on init`() = runTest {
        val (vm, _, _) = build(history = listOf(assistant("a1")))
        advanceUntilIdle()
        assertEquals("Ses", vm.uiState.value.sessionTitle)
        assertEquals(listOf("a1"), vm.uiState.value.messages.map { it.id })
        assertFalse(vm.uiState.value.isLoadingHistory)
    }

    @Test
    fun `text delta from this session creates and grows a live bubble`() = runTest {
        val (vm, _, stream) = build()
        advanceUntilIdle()

        stream.push(StreamEvent.TextDelta("s1", "p1", "Hel"))
        advanceUntilIdle()
        val liveFirst = vm.uiState.value.messages.single()
        assertEquals(MessageAssembler.liveMessageId("s1"), liveFirst.id)
        assertEquals("Hel", (liveFirst.parts.single() as MessagePart.TextPart).content)
        assertTrue(vm.uiState.value.isBusy)

        stream.push(StreamEvent.TextDelta("s1", "p1", "lo"))
        advanceUntilIdle()
        val grown = vm.uiState.value.messages.single()
        assertEquals("Hello", (grown.parts.single() as MessagePart.TextPart).content)
    }

    @Test
    fun `deltas from a different session are ignored`() = runTest {
        val (vm, _, stream) = build()
        advanceUntilIdle()
        stream.push(StreamEvent.TextDelta("other", "p1", "no"))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.messages.isEmpty())
    }

    @Test
    fun `tool finished updates the matching live tool part`() = runTest {
        val (vm, _, stream) = build()
        advanceUntilIdle()
        stream.push(StreamEvent.ToolCalled("s1", "c1", "read", "{}"))
        stream.push(StreamEvent.ToolFinished("s1", "c1", com.anomalyco.opencode.domain.model.ToolStatus.COMPLETED, "ok"))
        advanceUntilIdle()
        val tool = vm.uiState.value.messages.single().parts
            .filterIsInstance<MessagePart.ToolCallPart>().single()
        assertEquals(com.anomalyco.opencode.domain.model.ToolStatus.COMPLETED, tool.status)
    }

    @Test
    fun `session idle settles busy and marks reasoning finished`() = runTest {
        val (vm, _, stream) = build()
        advanceUntilIdle()
        stream.push(StreamEvent.ReasoningDelta("s1", "r1", "think "))
        advanceUntilIdle()
        stream.push(StreamEvent.SessionIdle("s1"))
        advanceUntilIdle()
        val state = vm.uiState.value
        assertFalse(state.isBusy)
        val reasoning = state.messages.single().parts
            .filterIsInstance<MessagePart.ReasoningPart>().single()
        assertTrue(reasoning.isFinished)
    }

    @Test
    fun `send appends user bubble then swaps in server message and forwards agent`() = runTest {
        val (vm, sessions, _) = build()
        advanceUntilIdle()
        sessions.sendResult = { _ ->
            Result.success(ChatMessage(id = "srv", sessionId = "s1", role = MessageRole.USER))
        }
        val gate = CompletableDeferred<Unit>()
        sessions.sendGate = gate

        vm.onInputChange("  ")
        vm.send() // blank -> no-op
        assertNull(sessions.lastPrompt)

        vm.onInputChange("ship it")
        vm.onAgentChange(AgentMode.PLAN)
        vm.send()

        // Optimistic user bubble + sending flag hold while the request is pending.
        assertEquals(1, vm.uiState.value.messages.size)
        assertTrue(vm.uiState.value.isSending)

        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isSending)
        assertEquals("ship it" to "plan", sessions.lastPrompt)
        assertEquals(listOf("srv"), vm.uiState.value.messages.map { it.id })
        assertEquals("", vm.uiState.value.input)
    }

    @Test
    fun `send rolls back optimistic bubble on failure`() = runTest {
        val (vm, sessions, _) = build()
        advanceUntilIdle()
        sessions.sendResult = { Result.failure(Exception("gönderilemedi")) }
        val gate = CompletableDeferred<Unit>()
        sessions.sendGate = gate

        vm.onInputChange("hi")
        vm.send()
        assertEquals(1, vm.uiState.value.messages.size)

        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.messages.isEmpty())
        assertEquals("gönderilemedi", vm.uiState.value.error)
        assertFalse(vm.uiState.value.isBusy)
    }

    @Test
    fun `sending a new prompt rotates the prior live bubble to a stable id`() = runTest {
        val (vm, sessions, stream) = build()
        advanceUntilIdle()
        stream.push(StreamEvent.TextDelta("s1", "p1", "answer"))
        advanceUntilIdle()
        assertEquals(
            MessageAssembler.liveMessageId("s1"),
            vm.uiState.value.messages.single().id,
        )
        sessions.sendResult = { Result.success(ChatMessage(id = "srv", role = MessageRole.USER)) }
        vm.onInputChange("next")
        vm.send()
        val ids = vm.uiState.value.messages.map { it.id }
        assertFalse(MessageAssembler.liveMessageId("s1") in ids)
        assertTrue(ids.any { it.startsWith("assistant-s1-") })
    }
}

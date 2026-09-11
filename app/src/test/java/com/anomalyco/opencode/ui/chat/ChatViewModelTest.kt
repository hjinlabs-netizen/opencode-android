package com.anomalyco.opencode.ui.chat

import com.anomalyco.opencode.domain.model.ChatMessage
import com.anomalyco.opencode.domain.model.MessagePart
import com.anomalyco.opencode.domain.model.MessageRole
import com.anomalyco.opencode.domain.model.ModelInfo
import com.anomalyco.opencode.domain.model.PermissionDecision
import com.anomalyco.opencode.domain.model.PermissionRequest
import com.anomalyco.opencode.domain.model.ProviderConfig
import com.anomalyco.opencode.domain.model.QuestionRequest
import com.anomalyco.opencode.domain.model.Session
import com.anomalyco.opencode.domain.model.SessionSummary
import com.anomalyco.opencode.domain.model.StreamEvent
import com.anomalyco.opencode.domain.model.StreamStatus
import com.anomalyco.opencode.domain.repository.ChatStreamRepository
import com.anomalyco.opencode.domain.repository.InteractionRepository
import com.anomalyco.opencode.domain.repository.ModelRepository
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Test double with programmable returns and scripted stream events. */
private class FakeSessionRepository : SessionRepository {
    override val sessions: Flow<List<SessionSummary>> = MutableStateFlow(emptyList())

    var history: List<ChatMessage> = emptyList()
    var session: Session = Session(id = "s1", title = "Ses")
    var loadCalls = 0
    var sendResult: (String) -> Result<ChatMessage> = {
        Result.success(ChatMessage(id = "server-user", sessionId = "s1", role = MessageRole.USER))
    }
    var lastPrompt: Pair<String, String?>? = null
    var promptCount = 0

    /** When set, sendPrompt suspends until this gate completes (tests the optimistic state). */
    var sendGate: CompletableDeferred<Unit>? = null

    override suspend fun refreshSessions(): Result<List<SessionSummary>> = Result.success(emptyList())
    override suspend fun createSession(agent: String?, title: String?, directory: String?) =
        Result.success(session)
    override suspend fun getSession(sessionId: String) = Result.success(session)
    override suspend fun deleteSession(sessionId: String) = Result.success(Unit)
    override suspend fun loadMessages(sessionId: String): Result<List<ChatMessage>> {
        loadCalls++
        return Result.success(history)
    }
    override suspend fun sendPrompt(sessionId: String, text: String, agent: String?): Result<ChatMessage> {
        lastPrompt = text to agent
        promptCount++
        sendGate?.await()
        return sendResult(text)
    }

    val aborted = mutableListOf<String>()
    var abortResult: Result<Unit> = Result.success(Unit)
    override suspend fun abortSession(sessionId: String): Result<Unit> {
        aborted += sessionId
        return abortResult
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

/** Records interaction resolutions and lets tests drive their Result. */
private class FakeInteractionRepository : InteractionRepository {
    val permissions = mutableListOf<Pair<String, PermissionDecision>>()
    val questions = mutableListOf<Pair<String, List<String>>>()
    var permissionResult: Result<Unit> = Result.success(Unit)
    var questionResult: Result<Unit> = Result.success(Unit)

    override suspend fun respondPermission(requestId: String, decision: PermissionDecision): Result<Unit> {
        permissions += requestId to decision
        return permissionResult
    }

    override suspend fun respondQuestion(questionId: String, answers: List<String>): Result<Unit> {
        questions += questionId to answers
        return questionResult
    }
}

private class FakeModelRepository : ModelRepository {
    var providersResult: Result<List<ProviderConfig>> = Result.success(emptyList())
    var setResult: Result<Unit> = Result.success(Unit)
    var preferred: com.anomalyco.opencode.domain.model.ModelSelection? = null
    var fetchCalls = 0
    val setCalls = mutableListOf<Pair<String, String>>()

    override suspend fun fetchProviders(): Result<List<ProviderConfig>> {
        fetchCalls++
        return providersResult
    }

    override suspend fun setActiveModel(providerId: String, modelId: String): Result<Unit> {
        setCalls += providerId to modelId
        return setResult
    }

    override fun preferredSelection(): com.anomalyco.opencode.domain.model.ModelSelection? = preferred
}

/** All collaborators + the ViewModel built under test. */
private class ChatHarness(
    val viewModel: ChatViewModel,
    val sessions: FakeSessionRepository,
    val stream: FakeStreamRepository,
    val interactions: FakeInteractionRepository,
    val models: FakeModelRepository,
    val handle: SavedStateHandle,
) {
    operator fun component1() = viewModel
    operator fun component2() = sessions
    operator fun component3() = stream
    operator fun component4() = interactions
    operator fun component5() = models
    operator fun component6() = handle
}

class ChatViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun build(history: List<ChatMessage> = emptyList()): ChatHarness {
        val sessions = FakeSessionRepository().apply { this.history = history }
        val stream = FakeStreamRepository()
        val interactions = FakeInteractionRepository()
        val models = FakeModelRepository()
        val handle = SavedStateHandle(mapOf("sessionId" to "s1"))
        val vm = ChatViewModel(handle, sessions, stream, interactions, models)
        return ChatHarness(vm, sessions, stream, interactions, models, handle)
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
    fun `isSending unlocks when the first stream event acks the prompt during the long poll`() = runTest {
        val (vm, sessions, stream) = build()
        advanceUntilIdle()
        sessions.sendGate = CompletableDeferred() // POST stays pending until end of turn
        vm.onInputChange("hi")
        vm.send()
        assertTrue(vm.uiState.value.isSending)

        stream.push(StreamEvent.TextDelta("s1", "p1", "Go"))
        advanceUntilIdle()
        // The button must free up at the first token, not minutes later when
        // the end-of-turn long poll finally resolves.
        assertFalse(vm.uiState.value.isSending)
        assertTrue(vm.uiState.value.isBusy) // busy now carries the "working" state
    }

    @Test
    fun `step finish settles busy even when session idle never arrives`() = runTest {
        val (vm, _, stream) = build()
        advanceUntilIdle()
        stream.push(StreamEvent.ToolCalled("s1", "c1", "bash", "{}"))
        stream.push(StreamEvent.StepStarted("s1", "st", "run command"))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.isBusy)

        stream.push(StreamEvent.StepFinished("s1", "st", "run command"))
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isBusy)
    }

    @Test
    fun `trailing metadata events cannot re-arm the busy indicator`() = runTest {
        val (vm, _, stream) = build()
        advanceUntilIdle()
        stream.push(StreamEvent.TextDelta("s1", "p1", "x"))
        advanceUntilIdle()
        stream.push(StreamEvent.SessionIdle("s1"))
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isBusy)

        // Late bookkeeping for the finished turn must stay neutral.
        stream.push(StreamEvent.MessageUpdated("s1", "m9"))
        stream.push(StreamEvent.ToolFinished("s1", "c1", com.anomalyco.opencode.domain.model.ToolStatus.COMPLETED, null))
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isBusy)
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
    fun `failed send keeps the user message visible and surfaces the error`() = runTest {
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

        // The prompt often reached the server even when the (end-of-turn)
        // response fails: never erase the user bubble.
        assertEquals(1, vm.uiState.value.messages.size)
        assertEquals("gönderilemedi", vm.uiState.value.error)
        assertFalse(vm.uiState.value.isSending)
    }

    @Test
    fun `user message stays visible through the entire streaming lifecycle`() = runTest {
        val (vm, sessions, stream) = build()
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        sessions.sendGate = gate

        vm.onInputChange("merhaba")
        vm.send()

        fun assertUserVisible(phase: String) {
            assertTrue(
                "$phase: transcript wiped: ${vm.uiState.value.messages}",
                vm.uiState.value.messages.any { it.role == MessageRole.USER },
            )
        }

        // 1. optimistic bubble pending sendPrompt resolution.
        assertUserVisible("after send")

        // 2. agent starts answering while the send call is still in flight.
        stream.push(StreamEvent.TextDelta("s1", "p1", "Hel"))
        stream.push(StreamEvent.StepStarted("s1", "st1", "thinking"))
        advanceUntilIdle()
        assertUserVisible("during deltas")
        assertEquals(2, vm.uiState.value.messages.size) // user + live assistant

        stream.push(StreamEvent.ToolCalled("s1", "c1", "read", "{}"))
        advanceUntilIdle()
        assertUserVisible("during tool call")

        // 3. turn completes; live bubble merges in place, nothing is cleared.
        stream.push(StreamEvent.SessionIdle("s1"))
        advanceUntilIdle()
        assertUserVisible("after idle")
        assertTrue(vm.uiState.value.messages.isNotEmpty())

        // 4. end-of-turn response resolves: message swapped, still present.
        sessions.sendResult = {
            Result.success(ChatMessage(id = "srv-1", sessionId = "s1", role = MessageRole.USER))
        }
        gate.complete(Unit)
        advanceUntilIdle()
        assertUserVisible("after send resolved")
        assertTrue(vm.uiState.value.messages.any { it.id == "srv-1" })
    }

    @Test
    fun `send response with blank server id keeps the optimistic bubble`() = runTest {
        val (vm, sessions, _) = build()
        advanceUntilIdle()
        sessions.sendResult = {
            // Unrecognised response shape degrades to an empty MessageDto.
            Result.success(ChatMessage(id = "", sessionId = "s1", role = MessageRole.USER))
        }
        vm.onInputChange("hi")
        vm.send()
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.messages.size)
        assertTrue(vm.uiState.value.messages.single().id.startsWith("local-user-"))
    }

    @Test
    fun `reconnect resync with empty history never blanks the transcript`() = runTest {
        val (vm, sessions, stream) = build(history = listOf(assistant("m1")))
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.messages.size)

        stream.push(StreamEvent.TextDelta("s1", "p1", "partial"))
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.messages.size)

        // Mid-turn resync where the server momentarily reports no messages.
        sessions.history = emptyList()
        stream.statusFlow.value = StreamStatus.Error("drop")
        advanceUntilIdle()
        stream.statusFlow.value = StreamStatus.Connected
        advanceUntilIdle()

        val ids = vm.uiState.value.messages.map { it.id }
        assertEquals(listOf("m1", MessageAssembler.liveMessageId("s1")), ids)
    }

    @Test
    fun `turn completion reconciles the live bubble into history without duplicates`() = runTest {
        val (vm, sessions, stream) = build(history = listOf(assistant("m1")))
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        sessions.sendGate = gate

        vm.onInputChange("go")
        vm.send()
        stream.push(StreamEvent.TextDelta("s1", "p1", "partial answer"))
        advanceUntilIdle()
        // m1 + optimistic user + live bubble
        assertEquals(3, vm.uiState.value.messages.size)
        assertTrue(vm.uiState.value.isBusy)

        // The long poll resolves: the server has persisted the full turn.
        sessions.history = listOf(assistant("m1"), assistant("a-final"))
        sessions.sendResult = {
            Result.success(ChatMessage(id = "srv-user", sessionId = "s1", role = MessageRole.USER))
        }
        gate.complete(Unit)
        advanceUntilIdle()

        // Live bubble is dropped in favour of the authoritative rows (no dupes),
        // busy settles even if the session.idle event was missed.
        assertEquals(listOf("m1", "a-final"), vm.uiState.value.messages.map { it.id })
        assertFalse(vm.uiState.value.isBusy)
    }

    @Test
    fun `second send is ignored while the long-poll prompt is still in flight`() = runTest {
        val (vm, sessions, _) = build()
        advanceUntilIdle()
        sessions.sendGate = CompletableDeferred()

        vm.onInputChange("first")
        vm.send()
        val firstIds = vm.uiState.value.messages.map { it.id }

        vm.onInputChange("second")
        vm.send() // blocked by the isSending guard
        assertEquals(firstIds, vm.uiState.value.messages.map { it.id })
        assertEquals("second", vm.uiState.value.input)
        assertEquals("first", sessions.lastPrompt?.first)
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

    // ---- interactive permission / question flows ---------------------------

    private val permission = PermissionRequest(
        requestId = "per-1",
        sessionId = "s1",
        type = "edit",
        description = "Write file",
        path = "src/Main.kt",
    )

    private val question = QuestionRequest(
        questionId = "q-1",
        sessionId = "s1",
        text = "Which framework?",
        options = listOf("Compose", "Views"),
    )

    @Test
    fun `permission event queues an interaction without touching the transcript`() = runTest {
        val (vm, _, stream) = build()
        advanceUntilIdle()
        stream.push(StreamEvent.PermissionAsked("s1", permission))
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(listOf(PendingInteraction.Permission(permission)), state.pendingInteractions)
        assertTrue(state.interactionVisible)
        assertTrue(state.isBusy)
        assertTrue(state.messages.isEmpty()) // not merged into the message stream
    }

    @Test
    fun `duplicate permission request for same id is ignored`() = runTest {
        val (vm, _, stream) = build()
        advanceUntilIdle()
        stream.push(StreamEvent.PermissionAsked("s1", permission))
        stream.push(StreamEvent.PermissionAsked("s1", permission.copy(description = "changed")))
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.pendingInteractions.size)
    }

    @Test
    fun `answering permission removes it, reveals next queued interaction, and posts decision`() = runTest {
        val (vm, _, stream, interactions) = build()
        advanceUntilIdle()
        stream.push(StreamEvent.PermissionAsked("s1", permission))
        val second = permission.copy(requestId = "per-2")
        stream.push(StreamEvent.PermissionAsked("s1", second))
        advanceUntilIdle()

        vm.respondToPermission("per-1", PermissionDecision.ALLOW)
        advanceUntilIdle()

        assertEquals(listOf("per-1" to PermissionDecision.ALLOW), interactions.permissions)
        assertEquals(listOf(PendingInteraction.Permission(second)), vm.uiState.value.pendingInteractions)
    }

    @Test
    fun `permission reply failure surfaces error but keeps transcript clean`() = runTest {
        val (vm, _, stream, interactions) = build()
        advanceUntilIdle()
        interactions.permissionResult = Result.failure(Exception("sunucu reddetti"))
        stream.push(StreamEvent.PermissionAsked("s1", permission))
        advanceUntilIdle()

        vm.respondToPermission("per-1", PermissionDecision.DENY)
        advanceUntilIdle()

        assertEquals("sunucu reddetti", vm.uiState.value.error)
        assertTrue(vm.uiState.value.pendingInteractions.isEmpty())
    }

    @Test
    fun `question answer forwards selected labels to repository`() = runTest {
        val (vm, _, stream, interactions) = build()
        advanceUntilIdle()
        stream.push(StreamEvent.QuestionAsked("s1", question))
        advanceUntilIdle()
        assertEquals(PendingInteraction.Question(question), vm.uiState.value.pendingInteractions.single())

        vm.respondToQuestion("q-1", listOf("Compose"))
        advanceUntilIdle()

        assertEquals(listOf("q-1" to listOf("Compose")), interactions.questions)
        assertTrue(vm.uiState.value.pendingInteractions.isEmpty())
    }

    @Test
    fun `session idle clears queued interactions`() = runTest {
        val (vm, _, stream) = build()
        advanceUntilIdle()
        stream.push(StreamEvent.QuestionAsked("s1", question))
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.pendingInteractions.size)

        stream.push(StreamEvent.SessionIdle("s1"))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.pendingInteractions.isEmpty())
    }

    @Test
    fun `interactions from another session are ignored`() = runTest {
        val (vm, _, stream) = build()
        advanceUntilIdle()
        stream.push(StreamEvent.PermissionAsked("other", permission.copy(sessionId = "other")))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.pendingInteractions.isEmpty())
    }

    @Test
    fun `hiding then showing the interaction toggles visibility without answering`() = runTest {
        val (vm, _, stream, interactions) = build()
        advanceUntilIdle()
        stream.push(StreamEvent.PermissionAsked("s1", permission))

        vm.hideInteraction()
        assertFalse(vm.uiState.value.interactionVisible)
        assertEquals(1, vm.uiState.value.pendingInteractions.size)
        assertTrue(interactions.permissions.isEmpty())

        vm.showInteraction()
        assertTrue(vm.uiState.value.interactionVisible)
    }

    // ---- model picker -------------------------------------------------------

    private fun catalog(current: Boolean) = listOf(
        ProviderConfig(
            providerId = "anthropic",
            displayName = "Anthropic",
            models = listOf(
                ModelInfo("anthropic", "claude-a", "Claude A", isCurrent = current),
                ModelInfo("anthropic", "claude-b", "Claude B", isCurrent = false),
            ),
        ),
    )

    @Test
    fun `openModelPicker lazily loads the catalog and flags the current model`() = runTest {
        val (vm, _, _, _, models) = build()
        models.providersResult = Result.success(catalog(current = false))
        advanceUntilIdle()

        vm.openModelPicker()
        advanceUntilIdle()

        assertEquals(1, models.fetchCalls)
        assertTrue(vm.uiState.value.isModelPickerOpen)
        assertEquals(2, vm.uiState.value.providers.single().models.size)

        // Second open is served from cache.
        vm.openModelPicker()
        advanceUntilIdle()
        assertEquals(1, models.fetchCalls)
    }

    @Test
    fun `selectModel posts the switch, re-flags the catalog and closes the sheet`() = runTest {
        val (vm, _, _, _, models) = build()
        models.providersResult = Result.success(catalog(current = false))
        vm.loadProviders()
        advanceUntilIdle()
        vm.openModelPicker()

        val target = vm.uiState.value.providers.single().models[1]
        vm.selectModel(target)
        advanceUntilIdle()

        assertEquals(listOf("anthropic" to "claude-b"), models.setCalls)
        assertFalse(vm.uiState.value.isModelPickerOpen)
        assertEquals("claude-b", vm.uiState.value.currentModel?.modelId)
        val flags = vm.uiState.value.providers.single().models.map { it.isCurrent }
        assertEquals(listOf(false, true), flags)
    }

    @Test
    fun `failed model switch keeps the sheet open and surfaces the error`() = runTest {
        val (vm, _, _, _, models) = build()
        models.providersResult = Result.success(catalog(current = false))
        models.setResult = Result.failure(Exception("model kullanılamıyor"))
        vm.loadProviders()
        advanceUntilIdle()
        vm.openModelPicker()

        vm.selectModel(vm.uiState.value.providers.single().models[1])
        advanceUntilIdle()

        assertEquals("model kullanılamıyor", vm.uiState.value.error)
        assertTrue(vm.uiState.value.isModelPickerOpen)
        assertNull(vm.uiState.value.switchingModelId)
        // Catalog flags are untouched after a failed switch.
        assertEquals(0, vm.uiState.value.providers.flatMap { it.models }.count { it.isCurrent })
        assertNull(vm.uiState.value.currentModel)
    }

    // ---- Phase 4 resilience -------------------------------------------------

    @Test
    fun `stream recovery after an error triggers a transcript resync`() = runTest {
        val (vm, sessions, stream) = build()
        advanceUntilIdle()
        val baseline = sessions.loadCalls

        stream.statusFlow.value = StreamStatus.Error("dropped")
        advanceUntilIdle()
        assertEquals(baseline, sessions.loadCalls) // the drop itself must not resync

        stream.statusFlow.value = StreamStatus.Connected
        advanceUntilIdle()
        assertTrue(sessions.loadCalls > baseline)
        assertEquals(StreamStatus.Connected, vm.uiState.value.streamStatus)
    }

    @Test
    fun `resync adopts fresh history while preserving the in-flight live bubble`() = runTest {
        val fresh = assistant("m1")
        val (vm, sessions, stream) = build(history = listOf(fresh))
        advanceUntilIdle()

        // Agent starts answering → live bubble exists.
        stream.push(StreamEvent.TextDelta("s1", "p1", "par"))
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.messages.size)

        // Connection drops and recovers; meanwhile a *previous* turn landed.
        val landed = assistant("m2")
        sessions.history = listOf(fresh, landed)
        stream.statusFlow.value = StreamStatus.Error("dropped")
        advanceUntilIdle()
        stream.statusFlow.value = StreamStatus.Connected
        advanceUntilIdle()

        val ids = vm.uiState.value.messages.map { it.id }
        assertEquals(listOf("m1", "m2", MessageAssembler.liveMessageId("s1")), ids)
        // No duplicated live content: the partial is still exactly one bubble.
        assertEquals("par", (vm.uiState.value.messages.last().parts.single() as MessagePart.TextPart).content)
    }

    // ---- file context selection (Phase 5) ----------------------------------

    @Test
    fun `appendMention formats empty and non-empty prompts`() {
        assertEquals("@a/b.kt ", ChatViewModel.appendMention("", "a/b.kt"))
        assertEquals("x @a/b.kt ", ChatViewModel.appendMention("x ", "a/b.kt"))
        assertEquals(
            "şu dosyaya bak: @C:\\t\\build.gradle.kts ",
            ChatViewModel.appendMention("şu dosyaya bak:", """C:\t\build.gradle.kts"""),
        )
    }

    @Test
    fun `picked file from explorer appends mention and clears the nav result`() = runTest {
        val (vm, _, _, _, _, handle) = build()
        advanceUntilIdle()

        vm.onInputChange("şu dosyaya bak:")
        handle[ChatViewModel.PICKED_FILE_KEY] = """Desktop\t24\app\build.gradle.kts"""
        advanceUntilIdle()

        assertEquals(
            "şu dosyaya bak: @Desktop\\t24\\app\\build.gradle.kts ",
            vm.uiState.value.input,
        )
        // Result consumed: cleared so rotation does not re-append.
        assertNull(handle.get<String>(ChatViewModel.PICKED_FILE_KEY))

        // A second pick appends again.
        handle[ChatViewModel.PICKED_FILE_KEY] = "src/main.kt"
        advanceUntilIdle()
        assertTrue(vm.uiState.value.input.endsWith("@src/main.kt "))
    }

    @Test
    fun `session directory from server feeds the ui state`() = runTest {
        val (vm, sessions) = build()
        sessions.session = Session(id = "s1", title = "Ses", directory = """C:\work\t24""")
        vm.refresh()
        advanceUntilIdle()

        assertEquals("""C:\work\t24""", vm.uiState.value.directory)
    }

    // ---- Sprint A: turn token guard + resume reconciliation ------------------

    @Test
    fun `late resolution of an older send cannot clobber the newer turn`() = runTest {
        val (vm, sessions, stream) = build()
        advanceUntilIdle()
        val gate1 = CompletableDeferred<Unit>()
        sessions.sendGate = gate1

        sessions.sendResult = { Result.success(ChatMessage(id = "srv-1", sessionId = "s1", role = MessageRole.USER)) }
        vm.onInputChange("birinci")
        vm.send()
        // Stream acks turn 1: isSending clears, live bubble opens.
        stream.push(StreamEvent.TextDelta("s1", "p1", "cevap1"))
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isSending)

        // Turn 2 starts while turn 1's POST is still long-polling.
        val gate2 = CompletableDeferred<Unit>()
        sessions.sendGate = gate2
        sessions.sendResult = { Result.success(ChatMessage(id = "srv-2", sessionId = "s1", role = MessageRole.USER)) }
        vm.onInputChange("ikinci")
        vm.send()
        // Turn 2's own live bubble opens.
        stream.push(StreamEvent.TextDelta("s1", "p2", "cevap2"))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.isBusy)

        // Turn 1 resolves LATE: must not settle busy nor sync away turn 2.
        gate1.complete(Unit)
        advanceUntilIdle()
        assertTrue("late send-1 cleared isBusy", vm.uiState.value.isBusy)
        val live = vm.uiState.value.messages.firstOrNull {
            it.id == MessageAssembler.liveMessageId("s1")
        }
        assertNotNull("late send-1 sync dropped the newer live bubble", live)
        assertEquals("cevap2", (live!!.parts.single() as MessagePart.TextPart).content)

        // Turn 2 resolves normally: everything settles.
        gate2.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isBusy)
        assertFalse(vm.uiState.value.isSending)
    }

    @Test
    fun `onResume refreshes only while a turn is busy`() = runTest {
        val (vm, sessions, stream) = build()
        advanceUntilIdle()
        val baseline = sessions.loadCalls

        vm.onResume()
        advanceUntilIdle()
        assertEquals(baseline, sessions.loadCalls) // idle: no pointless reload

        stream.push(StreamEvent.TextDelta("s1", "p1", "devam"))
        advanceUntilIdle()
        vm.onResume()
        advanceUntilIdle()
        assertTrue(sessions.loadCalls > baseline)
    }

    // ---- Sprint C: turn controls (abort / retry) -----------------------------

    @Test
    fun `abort posts the server abort and settles busy state`() = runTest {
        val (vm, sessions, stream) = build()
        advanceUntilIdle()
        sessions.sendGate = CompletableDeferred() // long poll stays open
        vm.onInputChange("calisir misin")
        vm.send()
        stream.push(StreamEvent.TextDelta("s1", "p1", "u"))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.isBusy)

        vm.abort()
        advanceUntilIdle()

        assertEquals(listOf("s1"), sessions.aborted)
        assertFalse(vm.uiState.value.isBusy)
        assertFalse(vm.uiState.value.isSending)
    }

    @Test
    fun `abort while idle is a no-op`() = runTest {
        val (vm, sessions) = build()
        advanceUntilIdle()

        vm.abort()
        advanceUntilIdle()

        assertTrue(sessions.aborted.isEmpty())
    }

    @Test
    fun `late long-poll resolution after an abort cannot re-arm busy state`() = runTest {
        val (vm, sessions, stream) = build()
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        sessions.sendGate = gate
        vm.onInputChange("merhaba")
        vm.send()
        stream.push(StreamEvent.TextDelta("s1", "p1", "cevap"))
        advanceUntilIdle()

        vm.abort()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isBusy)

        // The abandoned POST finally resolves; the token guard must ignore it.
        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isBusy)
    }

    @Test
    fun `abort failure surfaces the error but still settles the UI`() = runTest {
        val (vm, sessions, stream) = build()
        advanceUntilIdle()
        sessions.sendGate = CompletableDeferred()
        vm.onInputChange("x")
        vm.send()
        stream.push(StreamEvent.TextDelta("s1", "p1", "y"))
        advanceUntilIdle()
        sessions.abortResult = Result.failure(Exception("sunucu reddetti"))

        vm.abort()
        advanceUntilIdle()

        assertEquals("sunucu reddetti", vm.uiState.value.error)
        assertFalse(vm.uiState.value.isBusy)
    }

    @Test
    fun `retry re-dispatches the last prompt as a fresh turn`() = runTest {
        val (vm, sessions, stream) = build()
        advanceUntilIdle()
        vm.onInputChange("ilki")
        vm.send()
        advanceUntilIdle()
        assertEquals(1, sessions.promptCount)
        stream.push(StreamEvent.SessionIdle("s1"))
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isBusy)
        assertEquals("ilki", vm.uiState.value.lastPrompt)

        sessions.sendGate = CompletableDeferred() // keep the retried turn in flight
        vm.retry()
        advanceUntilIdle()

        assertEquals(2, sessions.promptCount)
        assertEquals("ilki", sessions.lastPrompt?.first)
        assertTrue(vm.uiState.value.isBusy)
        assertEquals("", vm.uiState.value.input)
    }

    @Test
    fun `retry without a previous prompt is a no-op`() = runTest {
        val (vm, sessions) = build()
        advanceUntilIdle()

        vm.retry()
        advanceUntilIdle()

        assertNull(sessions.lastPrompt)
        assertFalse(vm.uiState.value.isBusy)
    }
}

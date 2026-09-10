package com.anomalyco.opencode.ui.session

import com.anomalyco.opencode.domain.model.ChatMessage
import com.anomalyco.opencode.domain.model.Session
import com.anomalyco.opencode.domain.model.SessionSummary
import com.anomalyco.opencode.domain.repository.SessionRepository
import com.anomalyco.opencode.util.MainDispatcherRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class SessionListViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class Fake : SessionRepository {
        val cache = MutableStateFlow<List<SessionSummary>>(emptyList())
        override val sessions: Flow<List<SessionSummary>> = cache
        var refreshResult: Result<List<SessionSummary>> = Result.success(emptyList())
        var createResult: Result<Session> = Result.success(Session(id = "new-1", title = "Yeni"))
        var refreshCalls = 0

        override suspend fun refreshSessions(): Result<List<SessionSummary>> {
            refreshCalls++
            refreshResult.getOrNull()?.let { cache.value = it }
            return refreshResult
        }

        override suspend fun createSession(agent: String?, title: String?) =
            createResult.also { r ->
                r.getOrNull()?.let { s -> cache.value = listOf(s.toSummary()) + cache.value }
            }

        override suspend fun getSession(sessionId: String) = Result.success(Session(id = sessionId))
        override suspend fun loadMessages(sessionId: String) = Result.success(emptyList<ChatMessage>())
        override suspend fun sendPrompt(sessionId: String, text: String, agent: String?) =
            Result.success(ChatMessage(id = "m", sessionId = sessionId))
    }

    @Test
    fun `refresh failure surfaces a friendly error`() = runTest {
        val fake = Fake().apply {
            refreshResult = Result.failure(Exception("Sunucuya bağlanılamadı"))
        }
        val vm = SessionListViewModel(fake)
        advanceUntilIdle()

        assertEquals("Sunucuya bağlanılamadı", vm.uiState.value.error)
        assertEquals(1, vm.uiState.value.let { fake.refreshCalls })
    }

    @Test
    fun `successful refresh populates sessions via the repository cache`() = runTest {
        val summaries = listOf(SessionSummary(id = "s1", title = "Bir", updatedAt = 100L))
        val fake = Fake().apply { refreshResult = Result.success(summaries) }
        val vm = SessionListViewModel(fake)
        advanceUntilIdle()

        assertEquals(summaries, vm.uiState.value.sessions)
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `createSession emits a one-shot navigation id until consumed`() = runTest {
        val vm = SessionListViewModel(Fake())
        advanceUntilIdle()

        vm.createSession()
        advanceUntilIdle()
        assertEquals("new-1", vm.uiState.value.createdSessionId)

        vm.onSessionOpened()
        assertNull(vm.uiState.value.createdSessionId)
    }

    @Test
    fun `createSession failure sets error without a navigation id`() = runTest {
        val fake = Fake().apply {
            createResult = Result.failure(Exception("Oturum açılamadı"))
        }
        val vm = SessionListViewModel(fake)
        advanceUntilIdle()

        vm.createSession()
        advanceUntilIdle()
        assertEquals("Oturum açılamadı", vm.uiState.value.error)
        assertNull(vm.uiState.value.createdSessionId)
    }
}

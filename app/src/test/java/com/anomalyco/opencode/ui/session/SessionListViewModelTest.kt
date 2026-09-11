package com.anomalyco.opencode.ui.session

import com.anomalyco.opencode.domain.model.ChatMessage
import com.anomalyco.opencode.domain.model.Session
import com.anomalyco.opencode.domain.model.SessionSummary
import com.anomalyco.opencode.domain.repository.SessionRepository
import com.anomalyco.opencode.domain.repository.WorkspaceRepository
import com.anomalyco.opencode.util.MainDispatcherRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private class Fake : SessionRepository {
    val cache = MutableStateFlow<List<SessionSummary>>(emptyList())
    override val sessions: Flow<List<SessionSummary>> = cache
    var refreshResult: Result<List<SessionSummary>> = Result.success(emptyList())
    var createResult: Result<Session> = Result.success(Session(id = "new-1", title = "Yeni"))
    var lastCreateDirectory: String? = "unset"
    val deleted = mutableListOf<String>()
    private val deletedOk = mutableListOf<String>()
    var deleteResult: (String) -> Result<Unit> = { Result.success(Unit) }

    override suspend fun refreshSessions(): Result<List<SessionSummary>> {
        // Server-realistic: successful deletions are reflected by refreshes.
        refreshResult.getOrNull()?.let { list ->
            cache.value = list.filterNot { it.id in deletedOk }
        }
        return refreshResult
    }

    override suspend fun createSession(agent: String?, title: String?, directory: String?): Result<Session> {
        lastCreateDirectory = directory
        return createResult.also { r ->
            r.getOrNull()?.let { s -> cache.value = listOf(s.toSummary()) + cache.value }
        }
    }

    override suspend fun getSession(sessionId: String) = Result.success(Session(id = sessionId))

    override suspend fun deleteSession(sessionId: String): Result<Unit> {
        deleted += sessionId
        return deleteResult(sessionId).also { r ->
            if (r.isSuccess) {
                deletedOk += sessionId
                cache.value = cache.value.filterNot { it.id == sessionId }
            }
        }
    }

    override suspend fun loadMessages(sessionId: String) = Result.success(emptyList<ChatMessage>())
    override suspend fun sendPrompt(sessionId: String, text: String, agent: String?) =
        Result.success(ChatMessage(id = "m", sessionId = sessionId))
}

private class FakeWorkspace : WorkspaceRepository {
    val dirs = MutableStateFlow<List<String>>(emptyList())
    override val recentDirectories: Flow<List<String>> = dirs
    override suspend fun rememberDirectory(directory: String) {
        dirs.value = (listOf(directory) + dirs.value).distinct()
    }
}

class SessionListViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class Harness(
        val repository: Fake = Fake(),
        val workspace: FakeWorkspace = FakeWorkspace(),
    ) {
        val viewModel = SessionListViewModel(repository, workspace)
    }

    @Test
    fun `refresh failure surfaces a friendly error`() = runTest {
        val h = Harness()
        h.repository.refreshResult = Result.failure(Exception("Sunucuya bağlanılamadı"))
        val vm = SessionListViewModel(h.repository, h.workspace)
        advanceUntilIdle()

        assertEquals("Sunucuya bağlanılamadı", vm.uiState.value.error)
    }

    @Test
    fun `successful refresh populates sessions via the repository cache`() = runTest {
        val summaries = listOf(SessionSummary(id = "s1", title = "Bir", updatedAt = 100L))
        val h = Harness(Fake().apply { refreshResult = Result.success(summaries) })
        advanceUntilIdle()

        assertEquals(summaries, h.viewModel.uiState.value.sessions)
        assertNull(h.viewModel.uiState.value.error)
    }

    // ---- new session flow ----

    @Test
    fun `quick create sends no directory and navigates`() = runTest {
        val h = Harness()
        advanceUntilIdle()

        h.viewModel.showNewSessionOptions()
        assertTrue(h.viewModel.uiState.value.showNewSessionOptions)
        h.viewModel.quickCreateSession()
        advanceUntilIdle()

        assertNull(h.repository.lastCreateDirectory)
        assertEquals("new-1", h.viewModel.uiState.value.createdSessionId)
        assertFalse(h.viewModel.uiState.value.showNewSessionOptions)
        assertTrue(h.workspace.dirs.value.isEmpty()) // default dir is not remembered
    }

    @Test
    fun `custom directory create trims input, passes payload and remembers it`() = runTest {
        val h = Harness()
        advanceUntilIdle()

        h.viewModel.showDirectoryDialog()
        h.viewModel.onDirectoryInputChange("  C:\\Users\\zuley\\Desktop\\t24  ")
        h.viewModel.createSessionWithDirectory()
        advanceUntilIdle()

        assertEquals("""C:\Users\zuley\Desktop\t24""", h.repository.lastCreateDirectory)
        assertEquals("new-1", h.viewModel.uiState.value.createdSessionId)
        assertEquals(listOf("""C:\Users\zuley\Desktop\t24"""), h.workspace.dirs.value)
        assertFalse(h.viewModel.uiState.value.showDirectoryDialog)
    }

    @Test
    fun `blank directory input never creates a session`() = runTest {
        val h = Harness()
        advanceUntilIdle()

        h.viewModel.onDirectoryInputChange("   ")
        h.viewModel.createSessionWithDirectory()
        advanceUntilIdle()

        assertEquals("unset", h.repository.lastCreateDirectory)
        assertNull(h.viewModel.uiState.value.createdSessionId)
    }

    @Test
    fun `failed create surfaces error without a navigation id`() = runTest {
        val h = Harness(
            Fake().apply { createResult = Result.failure(Exception("Oturum açılamadı")) },
        )
        advanceUntilIdle()

        h.viewModel.quickCreateSession()
        advanceUntilIdle()

        assertEquals("Oturum açılamadı", h.viewModel.uiState.value.error)
        assertNull(h.viewModel.uiState.value.createdSessionId)
    }

    // ---- multi-select deletion ----

    private fun threeSessions() = Fake().apply {
        refreshResult = Result.success(
            listOf(
                SessionSummary(id = "a", title = "A", updatedAt = 3),
                SessionSummary(id = "b", title = "B", updatedAt = 2),
                SessionSummary(id = "c", title = "C", updatedAt = 1),
            ),
        )
    }

    @Test
    fun `long press enters selection mode and taps toggle`() = runTest {
        val h = Harness(threeSessions())
        advanceUntilIdle()

        h.viewModel.onSessionLongClick("a")
        assertTrue(h.viewModel.uiState.value.selectionMode)

        h.viewModel.onSessionClick("b") // tap while selecting = toggle
        assertEquals(setOf("a", "b"), h.viewModel.uiState.value.selectedIds)

        h.viewModel.toggleSelection("a")
        h.viewModel.toggleSelection("b")
        // Last selection gone -> mode exits.
        assertFalse(h.viewModel.uiState.value.selectionMode)
        assertTrue(h.viewModel.uiState.value.selectedIds.isEmpty())
    }

    @Test
    fun `row tap outside selection mode navigates`() = runTest {
        val h = Harness(threeSessions())
        advanceUntilIdle()

        h.viewModel.onSessionClick("b")
        assertEquals("b", h.viewModel.uiState.value.createdSessionId)
        assertFalse(h.viewModel.uiState.value.selectionMode)
    }

    @Test
    fun `select all picks every visible session`() = runTest {
        val h = Harness(threeSessions())
        advanceUntilIdle()

        h.viewModel.selectAll()
        assertEquals(setOf("a", "b", "c"), h.viewModel.uiState.value.selectedIds)
    }

    @Test
    fun `delete requires confirmation then removes each selected session`() = runTest {
        val h = Harness(threeSessions())
        advanceUntilIdle()
        h.viewModel.selectAll()

        h.viewModel.requestDeleteSelection()
        assertTrue(h.viewModel.uiState.value.showDeleteConfirm)

        h.viewModel.confirmDelete()
        advanceUntilIdle()

        assertEquals(listOf("a", "b", "c"), h.repository.deleted.sorted())
        assertFalse(h.viewModel.uiState.value.selectionMode)
        assertFalse(h.viewModel.uiState.value.showDeleteConfirm)
        assertNull(h.viewModel.uiState.value.error)
        assertEquals(
            emptySet<String>(),
            h.viewModel.uiState.value.sessions.map { it.id }.toSet(),
        )
    }

    @Test
    fun `cancel keeps sessions intact`() = runTest {
        val h = Harness(threeSessions())
        advanceUntilIdle()
        h.viewModel.selectAll()
        h.viewModel.requestDeleteSelection()

        h.viewModel.cancelDelete()

        assertFalse(h.viewModel.uiState.value.showDeleteConfirm)
        assertTrue(h.repository.deleted.isEmpty())
    }

    @Test
    fun `partial delete failure reports count and keeps failed rows`() = runTest {
        val repository = threeSessions()
        repository.deleteResult = { id ->
            if (id == "b") Result.failure(Exception("nope")) else Result.success(Unit)
        }
        val h = Harness(repository)
        advanceUntilIdle()
        h.viewModel.selectAll()

        h.viewModel.confirmDelete()
        advanceUntilIdle()

        assertEquals("1 oturum silinemedi.", h.viewModel.uiState.value.error)
        assertFalse(h.viewModel.uiState.value.selectionMode)
        // Only the failed row survives in the cache.
        assertEquals(listOf("b"), h.viewModel.uiState.value.sessions.map { it.id })
    }
}

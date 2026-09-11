package com.anomalyco.opencode.data.repository

import com.anomalyco.opencode.util.InMemoryPreferenceStore
import com.anomalyco.opencode.util.testJson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Recent-working-directory persistence: ordering, dedup, cap and corruption. */
class WorkspaceRepositoryImplTest {

    private fun repository(store: InMemoryPreferenceStore = InMemoryPreferenceStore()) =
        WorkspaceRepositoryImpl(store, testJson())

    @Test
    fun `starts empty when nothing was persisted`() = runTest {
        assertEquals(emptyList<String>(), repository().recentDirectories.first())
    }

    @Test
    fun `remember moves the directory to the front and dedupes`() = runTest {
        val repo = repository()
        repo.rememberDirectory("""C:\work\a""")
        repo.rememberDirectory("""C:\Users\zuley\Desktop\t24""")
        repo.rememberDirectory("""C:\work\a""")

        assertEquals(
            listOf("""C:\work\a""", """C:\Users\zuley\Desktop\t24"""),
            repo.recentDirectories.first(),
        )
    }

    @Test
    fun `blank and whitespace directories are ignored`() = runTest {
        val repo = repository()
        repo.rememberDirectory("   ")
        repo.rememberDirectory("")
        assertEquals(emptyList<String>(), repo.recentDirectories.first())
    }

    @Test
    fun `input is trimmed before storing`() = runTest {
        val repo = repository()
        repo.rememberDirectory("  C:\\work\\b  ")
        assertEquals(listOf("""C:\work\b"""), repo.recentDirectories.first())
    }

    @Test
    fun `list is capped and survives a restart via the store`() = runTest {
        val store = InMemoryPreferenceStore()
        val repo = repository(store)
        (1..10).forEach { repo.rememberDirectory("C:\\work\\$it") }

        val recent = repo.recentDirectories.first()
        assertEquals(8, recent.size)
        assertEquals("""C:\work\10""", recent.first())

        // Fresh repository instance reads back the persisted JSON array.
        assertEquals(recent, repository(store).recentDirectories.first())
    }

    @Test
    fun `corrupted persisted value degrades to empty instead of throwing`() = runTest {
        val store = InMemoryPreferenceStore().apply {
            putString("recent_directories", "not-json[")
        }
        assertEquals(emptyList<String>(), repository(store).recentDirectories.first())
    }
}

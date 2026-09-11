package com.anomalyco.opencode.ui.files

import com.anomalyco.opencode.domain.model.FileContent
import com.anomalyco.opencode.domain.model.FileDiff
import com.anomalyco.opencode.domain.model.FileNode
import com.anomalyco.opencode.domain.repository.FileRepository
import com.anomalyco.opencode.util.MainDispatcherRule
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Expand-all / collapse-all control over the diff cards (Sprint B P1). */
class DiffViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeFiles(private val diffs: List<FileDiff>) : FileRepository {
        override suspend fun listDirectory(path: String) = Result.success(emptyList<FileNode>())
        override suspend fun readFile(path: String) = Result.failure<FileContent>(NotImplementedError())
        override suspend fun diffFile(path: String) = Result.failure<FileDiff>(NotImplementedError())
        override suspend fun workingTreeDiff() = Result.success(diffs)
    }

    private fun diff(name: String) = FileDiff(oldPath = name, newPath = name)

    private val three = listOf(diff("a.kt"), diff("b.kt"), diff("c.kt"))

    @Test
    fun `first file is expanded by default and toggleAll expands the rest`() = runTest {
        val vm = DiffViewModel(FakeFiles(three))
        advanceUntilIdle()

        assertEquals(setOf(0), vm.expanded.value)

        vm.toggleAll()
        assertEquals(setOf(0, 1, 2), vm.expanded.value)

        vm.toggleAll()
        assertEquals(emptySet<Int>(), vm.expanded.value)
    }

    @Test
    fun `partial selection then toggleAll expands all, again collapses all`() = runTest {
        val vm = DiffViewModel(FakeFiles(three))
        advanceUntilIdle()
        vm.toggle(2) // {0, 2}

        vm.toggleAll() // not all expanded -> expand all
        assertEquals(setOf(0, 1, 2), vm.expanded.value)

        vm.toggleAll() // all expanded -> collapse all
        assertEquals(emptySet<Int>(), vm.expanded.value)
    }
}

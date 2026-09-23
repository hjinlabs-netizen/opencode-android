package com.anomalyco.opencode.ui.files

import androidx.lifecycle.SavedStateHandle
import com.anomalyco.opencode.domain.error.OpenCodeError
import com.anomalyco.opencode.domain.error.OpenCodeException
import com.anomalyco.opencode.domain.model.FileContent
import com.anomalyco.opencode.domain.model.FileDiff
import com.anomalyco.opencode.domain.model.FileListing
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

    private class FakeFiles(
        private val diffs: List<FileDiff>,
        private val single: (String) -> Result<FileDiff> = { Result.failure(NotImplementedError()) },
    ) : FileRepository {
        override suspend fun listDirectory(path: String) = Result.success(FileListing())
        override suspend fun readFile(path: String) = Result.failure<FileContent>(NotImplementedError())
        override suspend fun diffFile(path: String) = single(path)
        override suspend fun workingTreeDiff() = Result.success(diffs)
    }

    private fun diff(name: String) = FileDiff(oldPath = name, newPath = name)

    private val three = listOf(diff("a.kt"), diff("b.kt"), diff("c.kt"))

    @Test
    fun `first file is expanded by default and toggleAll expands the rest`() = runTest {
        val vm = DiffViewModel(SavedStateHandle(), FakeFiles(three))
        advanceUntilIdle()

        assertEquals(setOf(0), vm.expanded.value)

        vm.toggleAll()
        assertEquals(setOf(0, 1, 2), vm.expanded.value)

        vm.toggleAll()
        assertEquals(emptySet<Int>(), vm.expanded.value)
    }

    @Test
    fun `partial selection then toggleAll expands all, again collapses all`() = runTest {
        val vm = DiffViewModel(SavedStateHandle(), FakeFiles(three))
        advanceUntilIdle()
        vm.toggle(2) // {0, 2}

        vm.toggleAll() // not all expanded -> expand all
        assertEquals(setOf(0, 1, 2), vm.expanded.value)

        vm.toggleAll() // all expanded -> collapse all
        assertEquals(emptySet<Int>(), vm.expanded.value)
    }

    @Test
    fun `target path loads only the matching single file diff`() = runTest {
        val vm = DiffViewModel(
            SavedStateHandle(mapOf(DiffViewModel.ARG_PATH to "b.kt")),
            FakeFiles(three, single = { Result.success(diff("b.kt")) }),
        )
        advanceUntilIdle()

        assertEquals(listOf("b.kt"), vm.uiState.value.files.map { it.path })
        assertEquals(null, vm.uiState.value.error)
        assertEquals(false, vm.uiState.value.noDiffForFile)
    }

    @Test
    fun `target path rejects a mismatched fallback file with the per-file empty state`() = runTest {
        // The repository falls back to an unrelated row when the path is absent;
        // the VM must reject it and show "No diff available for this file",
        // never the wrong file and never a transport snackbar.
        val vm = DiffViewModel(
            SavedStateHandle(mapOf(DiffViewModel.ARG_PATH to "missing.kt")),
            FakeFiles(three, single = { Result.success(diff("a.kt")) }),
        )
        advanceUntilIdle()

        assertEquals(emptyList<FileDiff>(), vm.uiState.value.files)
        assertEquals(true, vm.uiState.value.noDiffForFile)
        assertEquals("missing.kt", vm.uiState.value.targetPath)
        assertEquals(null, vm.uiState.value.error)
    }

    @Test
    fun `missing diff endpoint for the target resolves to the per-file empty state`() = runTest {
        val vm = DiffViewModel(
            SavedStateHandle(mapOf(DiffViewModel.ARG_PATH to "unchanged.kt")),
            FakeFiles(
                three,
                single = {
                    Result.failure(OpenCodeException(OpenCodeError.EndpointMissing("diff:unchanged.kt")))
                },
            ),
        )
        advanceUntilIdle()

        assertEquals(emptyList<FileDiff>(), vm.uiState.value.files)
        assertEquals(true, vm.uiState.value.noDiffForFile)
        assertEquals(null, vm.uiState.value.error)
    }

    @Test
    fun `a genuine transport failure still surfaces as an error, not the empty state`() = runTest {
        val vm = DiffViewModel(
            SavedStateHandle(mapOf(DiffViewModel.ARG_PATH to "a.kt")),
            FakeFiles(
                three,
                single = {
                    Result.failure(
                        OpenCodeException(OpenCodeError.Network(OpenCodeError.NetworkKind.Connect)),
                    )
                },
            ),
        )
        advanceUntilIdle()

        assertEquals(false, vm.uiState.value.noDiffForFile)
        assertEquals(OpenCodeError.Network(OpenCodeError.NetworkKind.Connect), vm.uiState.value.error)
    }
}

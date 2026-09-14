package com.anomalyco.opencode.ui.session

import androidx.lifecycle.SavedStateHandle
import com.anomalyco.opencode.domain.error.OpenCodeError
import com.anomalyco.opencode.domain.error.OpenCodeException
import com.anomalyco.opencode.domain.model.FileContent
import com.anomalyco.opencode.domain.model.FileDiff
import com.anomalyco.opencode.domain.model.FileListing
import com.anomalyco.opencode.domain.model.FileNode
import com.anomalyco.opencode.domain.model.ServerPath
import com.anomalyco.opencode.domain.repository.FileRepository
import com.anomalyco.opencode.util.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Repository double recording every browsed path (existing fake pattern). */
private class FakeFolderRepository : FileRepository {
    val calls = mutableListOf<String>()
    var listing: suspend (String) -> Result<FileListing> = { Result.success(FileListing()) }

    override suspend fun listDirectory(path: String): Result<FileListing> {
        calls += path
        return listing(path)
    }

    override suspend fun readFile(path: String) = Result.failure<FileContent>(NotImplementedError())
    override suspend fun diffFile(path: String) = Result.failure<FileDiff>(NotImplementedError())
    override suspend fun workingTreeDiff() = Result.success(emptyList<FileDiff>())
}

/**
 * W.2: server-side folder browsing state. Pins the W.0 contract - relative
 * paths only, `..` never sent, files unselectable, root selectable, typed
 * errors instead of raw server text.
 */
class FolderPickerViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun dir(
        path: String,
        name: String = path.substringAfterLast('\\'),
        absolute: String = "",
    ) = FileNode(path = path, name = name, isDirectory = true, absolute = absolute)

    private fun file(path: String, name: String = path.substringAfterLast('\\')) =
        FileNode(path = path, name = name, isDirectory = false)

    private fun viewModelWith(
        fake: FakeFolderRepository,
        initialPath: String? = null,
    ): FolderPickerViewModel {
        val handle = if (initialPath != null) SavedStateHandle(mapOf("path" to initialPath)) else SavedStateHandle()
        return FolderPickerViewModel(handle, fake)
    }

    @Test
    fun `init lists the project root and shows only directories`() = runTest {
        val fake = FakeFolderRepository().apply {
            listing = {
                Result.success(
                    FileListing(listOf(dir("Desktop\\", "Desktop"), dir("docs\\", "docs"), file("readme.md", "readme.md"))),
                )
            }
        }

        val vm = viewModelWith(fake)
        advanceUntilIdle()

        assertEquals(listOf(ServerPath.ROOT), fake.calls)
        assertEquals(listOf("Desktop", "docs"), vm.uiState.value.directories.map { it.name })
        assertEquals(ServerPath.ROOT, vm.uiState.value.currentPath)
        assertFalse("root has no parent", vm.uiState.value.canGoUp)
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun `enterFolder descends with a normalized path and rebuilds breadcrumbs`() = runTest {
        val fake = FakeFolderRepository().apply {
            listing = { Result.success(FileListing(listOf(dir("Desktop\\Android_CLI\\", "Android_CLI")))) }
        }
        val vm = viewModelWith(fake)
        advanceUntilIdle()

        vm.enterFolder(dir("Desktop\\Android_CLI\\", "Android_CLI")) // trailing separator from the server
        advanceUntilIdle()

        assertEquals(listOf(".", "Desktop\\Android_CLI"), fake.calls)
        assertEquals(
            listOf(".", "Desktop", "Desktop\\Android_CLI"),
            vm.uiState.value.breadcrumbs,
        )
        assertTrue(vm.uiState.value.canGoUp)
        assertEquals(1, vm.uiState.value.directories.size)
    }

    @Test
    fun `goUp re-lists remembered parent levels and never sends dotdot`() = runTest {
        val fake = FakeFolderRepository()
        val vm = viewModelWith(fake)
        advanceUntilIdle()

        vm.enterFolder(dir("Desktop\\X", "X"))
        advanceUntilIdle()
        vm.goUp() // -> Desktop
        advanceUntilIdle()
        vm.goUp() // -> root
        advanceUntilIdle()
        vm.goUp() // root: no-op, no server call
        advanceUntilIdle()

        assertEquals(listOf(".", "Desktop\\X", "Desktop", "."), fake.calls)
        assertFalse("no traversal ever left the VM", fake.calls.any { it.contains("..") })
        assertEquals(ServerPath.ROOT, vm.uiState.value.currentPath)
    }

    @Test
    fun `goToBreadcrumb jumps directly to an ancestor level`() = runTest {
        val fake = FakeFolderRepository()
        val vm = viewModelWith(fake, initialPath = "AppData\\Local\\Packages")
        advanceUntilIdle()

        vm.goToBreadcrumb("AppData")
        advanceUntilIdle()

        assertEquals(listOf("AppData\\Local\\Packages", "AppData"), fake.calls)
        assertEquals(listOf(".", "AppData"), vm.uiState.value.breadcrumbs)
    }

    @Test
    fun `root stays selectable and selection records normalized paths`() = runTest {
        val fake = FakeFolderRepository()
        val vm = viewModelWith(fake)
        advanceUntilIdle()

        assertNull(vm.uiState.value.selectedFolder)
        vm.selectCurrentFolder()
        assertEquals(ServerPath.ROOT, vm.uiState.value.selectedFolder)

        vm.selectFolder(dir("Desktop\\Sub\\", "Sub"))
        assertEquals("Desktop\\Sub", vm.uiState.value.selectedFolder)
    }

    @Test
    fun `failed listing sets the typed error and keeps the previous directories`() = runTest {
        var fail = false
        val fake = FakeFolderRepository().apply {
            listing = {
                if (fail) {
                    Result.failure(OpenCodeException(OpenCodeError.Http(500, null)))
                } else {
                    Result.success(FileListing(listOf(dir("Desktop", "Desktop"))))
                }
            }
        }
        val vm = viewModelWith(fake)
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.directories.size)

        fail = true
        vm.enterFolder(dir("Gone", "Gone"))
        advanceUntilIdle()

        assertEquals(OpenCodeError.Http(500, null), vm.uiState.value.error)
        assertFalse(vm.uiState.value.isLoading)
        assertEquals("stale listing stays visible", 1, vm.uiState.value.directories.size)

        vm.onErrorShown()
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `empty directory yields an empty list without error`() = runTest {
        val fake = FakeFolderRepository().apply {
            listing = { Result.success(FileListing()) }
        }

        val vm = viewModelWith(fake)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.directories.isEmpty())
        assertNull(vm.uiState.value.error)
        assertFalse(vm.uiState.value.listingTruncated)
    }

    @Test
    fun `the M4 truncation flag is carried into the picker state`() = runTest {
        val fake = FakeFolderRepository().apply {
            listing = { Result.success(FileListing(listOf(dir("a", "a")), truncated = true)) }
        }

        val vm = viewModelWith(fake)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.listingTruncated)
    }

    // ---- W.3: absolute-path resolution --------------------------------------
    @Test
    fun `selection prefers the server-provided absolute path`() = runTest {
        val fake = FakeFolderRepository().apply {
            listing = { path ->
                if (ServerPath.isRoot(path)) {
                    Result.success(FileListing(listOf(dir("Desktop", "Desktop", absolute = "C:\\Users\\zuley\\Desktop"))))
                } else {
                    Result.success(FileListing(listOf(dir("Desktop\\X", "X", absolute = "C:\\Users\\zuley\\Desktop\\X"))))
                }
            }
        }
        val vm = viewModelWith(fake)
        advanceUntilIdle()
        // The root's own anchor is the parent of its child's absolute path.
        assertEquals("C:\\Users\\zuley", vm.uiState.value.currentAbsolute)

        vm.enterFolder(dir("Desktop", "Desktop", absolute = "C:\\Users\\zuley\\Desktop"))
        advanceUntilIdle()
        assertEquals("C:\\Users\\zuley\\Desktop", vm.uiState.value.currentAbsolute)
        assertEquals("C:\\Users\\zuley\\Desktop", vm.selectCurrentFolder())

        assertEquals(
            "C:\\Users\\zuley\\Desktop\\X",
            vm.selectFolder(dir("Desktop\\X", "X", absolute = "C:\\Users\\zuley\\Desktop\\X")),
        )
        assertEquals("C:\\Users\\zuley\\Desktop\\X", vm.uiState.value.selectedFolder)
    }

    @Test
    fun `selection falls back to the relative path when no absolute anchor exists`() = runTest {
        val fake = FakeFolderRepository().apply {
            listing = { path ->
                if (ServerPath.isRoot(path)) {
                    Result.success(FileListing(listOf(dir("Desktop", "Desktop", absolute = "C:\\Users\\zuley\\Desktop"))))
                } else {
                    Result.success(FileListing()) // childless: nothing to derive from
                }
            }
        }
        val vm = viewModelWith(fake)
        advanceUntilIdle()

        vm.enterFolder(dir("Desktop", "Desktop", absolute = "C:\\Users\\zuley\\Desktop"))
        advanceUntilIdle()

        assertNull(vm.uiState.value.currentAbsolute)
        assertEquals("Desktop", vm.selectCurrentFolder())
    }

    @Test
    fun `goUp re-derives the anchor for the parent level`() = runTest {
        val fake = FakeFolderRepository().apply {
            listing = { path ->
                if (ServerPath.isRoot(path)) {
                    Result.success(FileListing(listOf(dir("Desktop", "Desktop", absolute = "C:\\Users\\zuley\\Desktop"))))
                } else {
                    Result.success(FileListing())
                }
            }
        }
        val vm = viewModelWith(fake)
        advanceUntilIdle()

        vm.enterFolder(dir("Desktop", "Desktop", absolute = "C:\\Users\\zuley\\Desktop"))
        advanceUntilIdle()
        assertNull(vm.uiState.value.currentAbsolute)

        vm.goUp()
        advanceUntilIdle()

        assertEquals(ServerPath.ROOT, vm.uiState.value.currentPath)
        assertEquals("C:\\Users\\zuley", vm.uiState.value.currentAbsolute)
    }

    @Test
    fun `listing reports the loading state while the server responds`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val fake = FakeFolderRepository().apply {
            listing = {
                gate.await()
                Result.success(FileListing(listOf(dir("Desktop", "Desktop"))))
            }
        }
        val vm = viewModelWith(fake)
        runCurrent()

        assertTrue(vm.uiState.value.isLoading)

        gate.complete(Unit)
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isLoading)
        assertEquals(1, vm.uiState.value.directories.size)
    }
}

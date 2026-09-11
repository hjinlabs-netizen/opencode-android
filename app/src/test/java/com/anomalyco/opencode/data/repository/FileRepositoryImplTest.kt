package com.anomalyco.opencode.data.repository

import com.anomalyco.opencode.data.remote.OpenCodeApi
import com.anomalyco.opencode.domain.model.DiffStatus
import com.anomalyco.opencode.domain.model.FileNode
import com.anomalyco.opencode.domain.model.ServerConfig
import com.anomalyco.opencode.util.FakeConnectionRepository
import com.anomalyco.opencode.util.MockResponse
import com.anomalyco.opencode.util.recordingClient
import com.anomalyco.opencode.util.testJson
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * File endpoint wiring: candidate-path fallback (`/fs` routes -> `/file`,
 * `/fs/diff` -> `/vcs/diff`), non-JSON (SPA HTML) degradation, query
 * parameter omission, endpoint probe caching (P2-2) and unified-diff parsing.
 */
class FileRepositoryImplTest {

    private val captured = mutableListOf<HttpRequestData>()

    private fun repository(
        connection: FakeConnectionRepository = FakeConnectionRepository(),
        script: (HttpRequestData) -> MockResponse,
    ) = FileRepositoryImpl(
        OpenCodeApi(recordingClient(captured, script), testJson()),
        connection,
        testJson(),
        CoroutineScope(UnconfinedTestDispatcher()),
    )

    private val html = MockResponse(
        body = "<!doctype html><html><head><title>OpenCode</title></head><body>SPA</body></html>",
        contentType = "text/html",
    )

    private fun pathOf(request: HttpRequestData) = request.url.encodedPath

    @Test
    fun `listDirectory qualifies entries lacking explicit paths`() = runTest {
        val repo = repository {
            MockResponse(
                body = """[
                    {"name":"src","type":"directory"},
                    {"name":"a.kt","type":"file","size":42},
                    {"name":"b.kt","path":"app/b.kt","isDirectory":false,"size":7}
                ]""",
            )
        }
        val result = repo.listDirectory("app")

        assertTrue(result.isSuccess)
        val request = captured.single()
        assertEquals("/fs/list", pathOf(request))
        assertEquals("app", request.url.parameters["path"])
        assertEquals(
            listOf(
                FileNode("app/src", "src", isDirectory = true),
                FileNode("app/a.kt", "a.kt", isDirectory = false, size = 42),
                FileNode("app/b.kt", "b.kt", isDirectory = false, size = 7),
            ),
            result.getOrThrow(),
        )
    }

    @Test
    fun `blank directory path omits the query parameter entirely`() = runTest {
        val repo = repository { MockResponse(body = "[]") }
        repo.listDirectory("").getOrThrow()

        assertTrue(captured.single().url.parameters["path"] == null)
    }

    @Test
    fun `html from fs list falls through the find family to the file endpoint`() = runTest {
        val repo = repository { request ->
            when (pathOf(request)) {
                "/file" -> MockResponse(
                    body = """{"type":"directory","path":"src","entries":[{"name":"a.kt","type":"file"}]}""",
                )
                else -> html
            }
        }
        val nodes = repo.listDirectory("src").getOrThrow()

        assertEquals(listOf("/fs/list", "/find", "/file/find", "/file"), captured.map(::pathOf))
        assertEquals(listOf(FileNode("src/a.kt", "a.kt", isDirectory = false)), nodes)
    }

    @Test
    fun `root listing probes find with the path dot default`() = runTest {
        // The live server rejects `/file` without a path (HTTP 400) and
        // `/fs/list` serves SPA html; `/find?path=.` is the working combo.
        val repo = repository { request ->
            when (pathOf(request)) {
                "/fs/list" -> html
                "/find" -> MockResponse(
                    body = """[{"name":"README.md","type":"file","path":"README.md"}]""",
                )
                else -> MockResponse(status = HttpStatusCode.BadRequest)
            }
        }
        val nodes = repo.listDirectory("").getOrThrow()

        assertEquals(listOf("/fs/list", "/find"), captured.map(::pathOf))
        assertEquals(".", captured.last().url.parameters["path"])
        assertEquals(listOf(FileNode("README.md", "README.md", isDirectory = false)), nodes)
    }

    @Test
    fun `bad-request on every list candidate surfaces a friendly error`() = runTest {
        val repo = repository { MockResponse(status = HttpStatusCode.BadRequest) }
        val result = repo.listDirectory("src")

        assertTrue(result.isFailure)
        assertEquals("Sunucu hatası: HTTP 400", result.exceptionOrNull()?.message)
        // All four candidates were probed before giving up.
        assertEquals(4, captured.size)
    }

    @Test
    fun `all-html endpoints surface a friendly error instead of NoTransformationFound`() = runTest {
        val repo = repository { html }
        val result = repo.listDirectory("src")

        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(message, message.contains("JSON yerine text/html"))
    }

    @Test
    fun `auth failure fails fast without probing further endpoints`() = runTest {
        val repo = repository { MockResponse(status = HttpStatusCode.Unauthorized) }
        val result = repo.listDirectory("src")

        assertTrue(result.isFailure)
        assertEquals(1, captured.size)
    }

    @Test
    fun `readFile returns text content for the requested path`() = runTest {
        val repo = repository {
            MockResponse(body = """{"content":"fun main() {}","type":"text"}""")
        }
        val result = repo.readFile("src/main.kt")

        assertEquals("src/main.kt", captured.last().url.parameters["path"])
        assertEquals("/fs/read", pathOf(captured.last()))
        val content = result.getOrThrow()
        assertEquals("src/main.kt", content.path)
        assertEquals("fun main() {}", content.content)
        assertEquals("text", content.mimeType)
    }

    @Test
    fun `workingTreeDiff parses embedded patch text into structured hunks`() = runTest {
        val patch = """
            --- a/a.kt
            +++ b/a.kt
            @@ -1 +1 @@
            -old
            +new
        """.trimIndent().replace("\n", "\\n")
        val repo = repository {
            MockResponse(
                body = """[{"file":"a.kt","status":"modified","patch":"$patch"}]""",
            )
        }
        val diffs = repo.workingTreeDiff().getOrThrow()

        assertEquals(1, diffs.size)
        val diff = diffs.single()
        assertEquals("a.kt", diff.path)
        assertEquals(DiffStatus.MODIFIED, diff.status)
        assertEquals(1, diff.additions)
        assertEquals(1, diff.deletions)
        assertEquals("/fs/diff", pathOf(captured.single()))
        assertTrue(captured.single().url.parameters["path"] == null)
    }

    @Test
    fun `workingTreeDiff falls back to vcs diff when fs diff serves html`() = runTest {
        val patch = """--- a/x.kt\n+++ b/x.kt\n@@ -1 +1 @@\n-a\n+b"""
        val repo = repository { request ->
            when (pathOf(request)) {
                "/fs/diff" -> html
                else -> MockResponse(body = """[{"file":"x.kt","patch":"$patch"}]""")
            }
        }
        val diffs = repo.workingTreeDiff().getOrThrow()

        assertEquals(listOf("/fs/diff", "/vcs/diff"), captured.map(::pathOf))
        assertEquals("x.kt", diffs.single().path)
        assertEquals(1, diffs.single().additions)
    }

    @Test
    fun `workingTreeDiff degrades to an empty list when no diff endpoint exists`() = runTest {
        val repo = repository { html }
        val result = repo.workingTreeDiff()

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().isEmpty())
        assertEquals(listOf("/fs/diff", "/vcs/diff"), captured.map(::pathOf))
    }

    @Test
    fun `non-git workspace 400 from diff endpoints degrades to an empty list`() = runTest {
        // "Git deposu: Hayır" — the server answers diff routes with HTTP 400
        // ("not a git repository"); the screen must show "Değişiklik yok".
        val repo = repository {
            MockResponse(status = HttpStatusCode.BadRequest, body = """{"error":"not a git repository"}""")
        }
        val result = repo.workingTreeDiff()

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().isEmpty())
        assertEquals(listOf("/fs/diff", "/vcs/diff"), captured.map(::pathOf))
    }

    @Test
    fun `diffFile selects the row matching the requested path`() = runTest {
        val repo = repository {
            MockResponse(
                body = """[
                    {"file":"other.kt","patch":"--- a/other.kt\n+++ b/other.kt\n@@ -1 +1 @@\n-x\n+y"},
                    {"file":"want.kt","patch":"--- a/want.kt\n+++ b/want.kt\n@@ -1 +1 @@\n-a\n+b"}
                ]""",
            )
        }
        val diff = repo.diffFile("want.kt").getOrThrow()

        assertEquals("want.kt", diff.path)
        assertEquals("want.kt", captured.single().url.parameters["path"])
    }

    @Test
    fun `not-found on every candidate maps to the friendly Turkish message`() = runTest {
        val repo = repository { MockResponse(status = HttpStatusCode.NotFound) }
        val result = repo.readFile("missing.txt")

        assertFalse(result.isSuccess)
        assertEquals("Kaynak sunucuda bulunamadı.", result.exceptionOrNull()?.message)
    }

    @Test
    fun `no server configured fails without an HTTP call`() = runTest {
        val repo = FileRepositoryImpl(
            OpenCodeApi(recordingClient(captured) { MockResponse() }, testJson()),
            FakeConnectionRepository(server = null),
            testJson(),
            CoroutineScope(UnconfinedTestDispatcher()),
        )
        // requireActiveServer waits 3s (virtual time advances in runTest) then throws.
        val result = repo.listDirectory("")

        assertTrue(result.isFailure)
        assertTrue(captured.isEmpty())
    }

    // ---- endpoint probe caching (Sprint C, P2-2) ----------------------------

    private val listScript: (HttpRequestData) -> MockResponse = { request ->
        when (pathOf(request)) {
            "/fs/list" -> html // SPA fallback: miss
            else -> MockResponse(body = """[{"name":"a.kt","type":"file","path":"a.kt"}]""")
        }
    }

    @Test
    fun `winning endpoint is memoized so routine browsing skips re-probing`() = runTest {
        val repo = repository(script = listScript)

        repo.listDirectory("x")
        assertEquals(listOf("/fs/list", "/find"), captured.map(::pathOf))
        assertEquals("/find", repo.cachedEndpoint("http://srv:4096", "list"))

        captured.clear()
        repo.listDirectory("y")
        repo.listDirectory("z")
        // One request per call, straight at the winner.
        assertEquals(listOf("/find", "/find"), captured.map(::pathOf))
    }

    @Test
    fun `list read and diff winners are cached independently`() = runTest {
        val repo = repository { request ->
            when (pathOf(request)) {
                "/fs/list", "/fs/read", "/fs/diff" -> html
                "/file" -> MockResponse(body = """{"content":"x"}""")
                "/find", "/file/find" -> MockResponse(body = "[]")
                else -> MockResponse(body = "[]")
            }
        }
        repo.listDirectory("a")
        repo.readFile("a.kt")
        repo.workingTreeDiff()

        assertEquals("/find", repo.cachedEndpoint("http://srv:4096", "list"))
        assertEquals("/file", repo.cachedEndpoint("http://srv:4096", "read"))
        assertEquals("/vcs/diff", repo.cachedEndpoint("http://srv:4096", "diff"))
    }

    @Test
    fun `changing server configuration invalidates the endpoint cache`() = runTest {
        val connection = FakeConnectionRepository()
        val repo = repository(connection = connection, script = listScript)
        repo.listDirectory("x")
        assertEquals("/find", repo.cachedEndpoint("http://srv:4096", "list"))

        // Same URL, different token (server swapped behind the address).
        connection.configFlow.value = ServerConfig("http://srv:4096/", "tok2")

        assertNull(repo.cachedEndpoint("http://srv:4096", "list"))
        captured.clear()
        repo.listDirectory("y")
        // Full re-probe after invalidation.
        assertEquals(listOf("/fs/list", "/find"), captured.map(::pathOf))
    }

    @Test
    fun `a cached winner that starts failing is demoted and re-probed`() = runTest {
        var fsListWorks = true
        val repo = repository { request ->
            when {
                pathOf(request) == "/fs/list" && fsListWorks ->
                    MockResponse(body = """[{"name":"a.kt","type":"file","path":"a.kt"}]""")
                pathOf(request) == "/fs/list" -> html
                else -> MockResponse(body = "[]")
            }
        }
        repo.listDirectory("x") // winner = /fs/list
        assertEquals("/fs/list", repo.cachedEndpoint("http://srv:4096", "list"))

        fsListWorks = false // server updated and lost the route
        captured.clear()
        repo.listDirectory("y")

        assertEquals(listOf("/fs/list", "/find"), captured.map(::pathOf))
        assertEquals("/find", repo.cachedEndpoint("http://srv:4096", "list"))
    }
}

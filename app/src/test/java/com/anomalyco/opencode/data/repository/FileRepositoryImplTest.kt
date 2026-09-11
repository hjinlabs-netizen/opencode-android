package com.anomalyco.opencode.data.repository

import com.anomalyco.opencode.data.remote.OpenCodeApi
import com.anomalyco.opencode.domain.model.DiffStatus
import com.anomalyco.opencode.domain.model.FileNode
import com.anomalyco.opencode.util.FakeConnectionRepository
import com.anomalyco.opencode.util.MockResponse
import com.anomalyco.opencode.util.recordingClient
import com.anomalyco.opencode.util.testJson
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * File endpoint wiring: candidate-path fallback (`/fs` routes -> `/file`,
 * `/fs/diff` -> `/vcs/diff`), non-JSON (SPA HTML) degradation, query
 * parameter omission and unified-diff parsing of server patch text.
 */
class FileRepositoryImplTest {

    private val captured = mutableListOf<HttpRequestData>()

    private fun repository(script: (HttpRequestData) -> MockResponse) = FileRepositoryImpl(
        OpenCodeApi(recordingClient(captured, script), testJson()),
        FakeConnectionRepository(),
        testJson(),
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
    fun `html from fs list falls through to the file endpoint`() = runTest {
        val repo = repository { request ->
            when (pathOf(request)) {
                "/fs/list" -> html
                else -> MockResponse(
                    body = """{"type":"directory","path":"src","entries":[{"name":"a.kt","type":"file"}]}""",
                )
            }
        }
        val nodes = repo.listDirectory("src").getOrThrow()

        assertEquals(listOf("/fs/list", "/file"), captured.map(::pathOf))
        assertEquals(listOf(FileNode("src/a.kt", "a.kt", isDirectory = false)), nodes)
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
        )
        // requireActiveServer waits 3s (virtual time advances in runTest) then throws.
        val result = repo.listDirectory("")

        assertTrue(result.isFailure)
        assertTrue(captured.isEmpty())
    }
}

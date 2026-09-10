package com.anomalyco.opencode.data.repository

import com.anomalyco.opencode.data.remote.OpenCodeApi
import com.anomalyco.opencode.domain.model.DiffStatus
import com.anomalyco.opencode.domain.model.FileNode
import com.anomalyco.opencode.util.FakeConnectionRepository
import com.anomalyco.opencode.util.MockResponse
import com.anomalyco.opencode.util.recordingClient
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * File endpoint wiring (paths, query parameters) and DTO -> domain decoding,
 * including unified-diff parsing of server patch text.
 */
class FileRepositoryImplTest {

    private val captured = mutableListOf<HttpRequestData>()

    private fun repository(script: (HttpRequestData) -> MockResponse) = FileRepositoryImpl(
        OpenCodeApi(recordingClient(captured, script)),
        FakeConnectionRepository(),
    )

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
        assertEquals("/fs/list", request.url.encodedPath)
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
    fun `readFile returns text content for the requested path`() = runTest {
        val repo = repository {
            MockResponse(body = """{"content":"fun main() {}","type":"text"}""")
        }
        val result = repo.readFile("src/main.kt")

        assertEquals("src/main.kt", requestUrl().parameters["path"])
        assertEquals("/fs/read", requestUrl().encodedPath)
        val content = result.getOrThrow()
        assertEquals("src/main.kt", content.path)
        assertEquals("fun main() {}", content.content)
        assertEquals("text", content.mimeType)
    }

    private fun requestUrl() = captured.last().url

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
        assertEquals("/fs/diff", captured.single().url.encodedPath)
        assertTrue(captured.single().url.parameters["path"] == null)
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
    fun `not-found maps to the friendly Turkish message`() = runTest {
        val repo = repository { MockResponse(status = HttpStatusCode.NotFound) }
        val result = repo.readFile("missing.txt")

        assertFalse(result.isSuccess)
        assertEquals("Kaynak sunucuda bulunamadı.", result.exceptionOrNull()?.message)
    }

    @Test
    fun `no server configured fails without an HTTP call`() = runTest {
        val repo = FileRepositoryImpl(
            OpenCodeApi(recordingClient(captured) { MockResponse() }),
            FakeConnectionRepository(server = null),
        )
        // requireActiveServer waits 3s (virtual time advances in runTest) then throws.
        val result = repo.listDirectory("")

        assertTrue(result.isFailure)
        assertTrue(captured.isEmpty())
    }
}

package com.anomalyco.opencode.data.repository

import com.anomalyco.opencode.data.remote.OpenCodeApi
import com.anomalyco.opencode.domain.model.SessionSummary
import com.anomalyco.opencode.util.FakeConnectionRepository
import com.anomalyco.opencode.util.MockResponse
import com.anomalyco.opencode.util.postedJson
import com.anomalyco.opencode.util.recordingClient
import com.anomalyco.opencode.util.testJson
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM coverage for the session repository now that server resolution is
 * unified behind [com.anomalyco.opencode.domain.repository.ConnectionRepository]
 * (Sprint A P0-1): payloads, cache mutations and delete tolerance are all
 * testable with MockEngine — no Android secure store involved.
 */
class SessionRepositoryImplTest {

    private val captured = mutableListOf<HttpRequestData>()

    private fun repository(
        server: FakeConnectionRepository = FakeConnectionRepository(),
        script: (HttpRequestData) -> MockResponse,
    ) = SessionRepositoryImpl(
        OpenCodeApi(recordingClient(captured, script), testJson()),
        server,
    )

    private val sessionJson = """{"id":"s1","title":"Merhaba","directory":"C:\\work\\t24","time":{"created":1,"updated":2}}"""

    @Test
    fun `refreshSessions decodes the list into the cache`() = runTest {
        val repo = repository { MockResponse(body = """[$sessionJson]""") }
        val result = repo.refreshSessions()

        assertEquals(
            listOf(SessionSummary(id = "s1", title = "Merhaba", createdAt = 1L, updatedAt = 2L)),
            result.getOrThrow(),
        )
        assertEquals(
            listOf("s1"),
            repo.sessions.first().map { it.id },
        )
        assertEquals("/session", captured.single().url.encodedPath)
    }

    @Test
    fun `createSession posts the directory payload and caches the result`() = runTest {
        val repo = repository { MockResponse(status = HttpStatusCode.Created, body = sessionJson) }
        val created = repo.createSession(directory = """C:\work\t24""").getOrThrow()

        val request = captured.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals(
            """{"directory":"C:\\work\\t24"}""",
            request.postedJson(),
        )
        assertEquals("""C:\work\t24""", created.directory)
        assertEquals(listOf("s1"), repo.sessions.first().map { it.id })
    }

    @Test
    fun `quick create posts a bare object`() = runTest {
        val repo = repository { MockResponse(body = sessionJson) }
        repo.createSession().getOrThrow()

        assertEquals("{}", captured.single().postedJson())
    }

    @Test
    fun `delete success removes the row from the cache`() = runTest {
        val repo = repository { request ->
            when (request.method) {
                HttpMethod.Post -> MockResponse(body = sessionJson)
                HttpMethod.Delete -> MockResponse(body = sessionJson)
                else -> MockResponse(body = "[]")
            }
        }
        repo.createSession().getOrThrow()
        assertEquals(1, repo.sessions.first().size)

        assertTrue(repo.deleteSession("s1").isSuccess)
        assertTrue(repo.sessions.first().isEmpty())
        val delete = captured.single { it.method == HttpMethod.Delete }
        assertEquals("/session/s1", delete.url.encodedPath)
    }

    @Test
    fun `delete tolerates 404 as already-gone`() = runTest {
        val repo = repository { request ->
            when (request.method) {
                HttpMethod.Post -> MockResponse(body = sessionJson)
                else -> MockResponse(status = HttpStatusCode.NotFound)
            }
        }
        repo.createSession().getOrThrow()

        assertTrue(repo.deleteSession("s1").isSuccess)
        assertTrue(repo.sessions.first().isEmpty())
    }

    @Test
    fun `delete failure keeps the row and reports friendly error`() = runTest {
        val repo = repository { request ->
            when (request.method) {
                HttpMethod.Post -> MockResponse(body = sessionJson)
                else -> MockResponse(status = HttpStatusCode.InternalServerError)
            }
        }
        repo.createSession().getOrThrow()

        val result = repo.deleteSession("s1")
        assertTrue(result.isFailure)
        assertEquals(
            "Sunucu hatası (HTTP 500) — OpenCode servisini kontrol et.",
            result.exceptionOrNull()?.message,
        )
        assertEquals(1, repo.sessions.first().size)
    }

    @Test
    fun `unconfigured server fails without any HTTP call`() = runTest {
        val repo = repository(FakeConnectionRepository(server = null)) { MockResponse() }
        val result = repo.refreshSessions()

        assertTrue(result.isFailure)
        assertTrue(captured.isEmpty())
    }

    @Test
    fun `sendPrompt forwards agent and returns the server message`() = runTest {
        val repo = repository {
            MockResponse(body = """{"info":{"id":"m1","role":"user","sessionID":"s1"},"parts":[{"id":"p1","type":"text","text":"selam"}]}""")
        }
        val message = repo.sendPrompt("s1", "selam", agent = "plan").getOrThrow()

        assertEquals("m1", message.id)
        assertEquals("selam", (message.parts.single() as com.anomalyco.opencode.domain.model.MessagePart.TextPart).content)
        val body = captured.single().postedJson()
        assertTrue(body.contains(""""agent":"plan""""))
        assertTrue(body.contains(""""text":"selam""""))
    }

    @Test
    fun `getSession caches the row for list observers`() = runTest {
        val repo = repository { MockResponse(body = sessionJson) }
        val session = repo.getSession("s1").getOrThrow()

        assertEquals("""C:\work\t24""", session.directory)
        assertEquals(listOf("s1"), repo.sessions.first().map { it.id })
    }
}

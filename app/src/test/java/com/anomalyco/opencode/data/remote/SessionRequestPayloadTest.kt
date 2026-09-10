package com.anomalyco.opencode.data.remote

import com.anomalyco.opencode.data.remote.dto.ConfigDto
import com.anomalyco.opencode.data.remote.dto.CreateSessionRequest
import com.anomalyco.opencode.data.remote.dto.PartInputDto
import com.anomalyco.opencode.data.remote.dto.PermissionResponseRequest
import com.anomalyco.opencode.data.remote.dto.SendMessageRequest
import com.anomalyco.opencode.di.NetworkModule
import com.anomalyco.opencode.util.MockResponse
import com.anomalyco.opencode.util.postedJson
import com.anomalyco.opencode.util.recordingClient
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for the HTTP 400 on `POST /session`: the OpenCode server
 * schema rejects explicit nulls, so unset optional fields must be ABSENT
 * from the request body (`{}`), never `"title":null`. Verified end-to-end
 * through `OpenCodeApi` on the PRODUCTION Json config.
 */
class SessionRequestPayloadTest {

    private val captured = mutableListOf<HttpRequestData>()

    private val api = OpenCodeApi(
        recordingClient(captured) { MockResponse(body = """{"id":"s-new","title":""}""") },
    )

    private fun json() = NetworkModule.opencodeJson()

    @Test
    fun `createSession with no fields posts a bare json object`() = runTest {
        api.createSession("http://srv:4096", "", CreateSessionRequest())

        val request = captured.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("/session", request.url.encodedPath)
        // After ContentNegotiation the type lives on the outgoing content.
        val contentType = request.headers[HttpHeaders.ContentType]
            ?: (request.body as io.ktor.http.content.OutgoingContent).contentType.toString()
        assertEquals("application/json", contentType.substringBefore(';'))
        assertEquals("{}", request.postedJson())
        assertFalse(request.postedJson().contains("null"))
    }

    @Test
    fun `createSession includes only the fields that are set`() = runTest {
        api.createSession("http://srv:4096", "", CreateSessionRequest(title = "Merhaba"))
        assertEquals("""{"title":"Merhaba"}""", captured.single().postedJson())
    }

    @Test
    fun `createSession keeps both fields when both are provided`() = runTest {
        api.createSession(
            "http://srv:4096",
            "",
            CreateSessionRequest(title = "T", agent = "build"),
        )
        val body = captured.single().postedJson()
        assertTrue(body.contains(""""title":"T""""))
        assertTrue(body.contains(""""agent":"build""""))
    }

    @Test
    fun `sendPrompt never serializes a null agent`() = runTest {
        api.sendPrompt(
            "http://srv:4096",
            "",
            "s1",
            SendMessageRequest(parts = listOf(PartInputDto(text = "hi"))),
        )
        assertEquals(
            """{"parts":[{"type":"text","text":"hi"}]}""",
            captured.single().postedJson(),
        )
    }

    @Test
    fun `resolution payloads are unaffected and stay null-free`() {
        assertEquals(
            """{"response":"once"}""",
            json().encodeToString(PermissionResponseRequest("once")),
        )
    }

    @Test
    fun `decoding still tolerates explicit nulls in server responses`() {
        val config = json().decodeFromString(ConfigDto.serializer(), """{"model":null}""")
        assertNull(config.toSelection())
    }

    @Test
    fun `sendPrompt never fails on unexpected response shapes`() = runTest {
        // An accepted prompt must not be reported as a send failure just
        // because the server's end-of-turn response shape surprises us.
        for (body in listOf("[]", "\"nope\"", """{"id":"m1","role":"user"}""", "not json")) {
            val localCaptured = mutableListOf<HttpRequestData>()
            val localApi = OpenCodeApi(
                recordingClient(localCaptured) { MockResponse(body = body) },
            )
            val message = localApi.sendPrompt(
                "http://srv:4096",
                "",
                "s1",
                SendMessageRequest(parts = listOf(PartInputDto(text = "x"))),
            )
            assertEquals(
                body,
                "",
                message.info.id, // degrades to an empty envelope at worst
            )
        }
    }
}

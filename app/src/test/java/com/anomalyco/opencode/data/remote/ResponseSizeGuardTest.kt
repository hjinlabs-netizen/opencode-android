package com.anomalyco.opencode.data.remote

import com.anomalyco.opencode.data.PayloadLimits
import com.anomalyco.opencode.data.remote.stream.SseEventTransport
import com.anomalyco.opencode.di.NetworkModule
import com.anomalyco.opencode.domain.error.OpenCodeError
import com.anomalyco.opencode.domain.model.ServerConfig
import com.anomalyco.opencode.util.FakeConnectionRepository
import com.anomalyco.opencode.util.SseTestServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headers
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sprint M.1: the HTTP-boundary size guard. The mechanism is the bounded
 * reads inside `OpenCodeApi` (the transformResponseBody plugin hook only
 * fires for typed `body<T>()` receives — proven during implementation to
 * bypass raw `bodyAsText()` reads, i.e. the biggest payloads). Covers the
 * full approved contract: below/at/over budget, Content-Length fast
 * rejection, counting without a declared length, per-endpoint budgets, and
 * the structural SSE exemption proven over a real socket with an
 * over-budget stream.
 */
class ResponseSizeGuardTest {

    private val json = NetworkModule.provideJson()

    private fun api(
        body: ByteArray,
        contentType: String = "application/json",
        declaredLength: Long? = null,
        asChannel: Boolean = false,
        status: HttpStatusCode = HttpStatusCode.OK,
    ): OpenCodeApi = OpenCodeApi(
        HttpClient(
            MockEngine {
                val headers = headers {
                    append(HttpHeaders.ContentType, contentType)
                    if (declaredLength != null) append(HttpHeaders.ContentLength, declaredLength.toString())
                }
                if (asChannel) {
                    respond(content = ByteReadChannel(body), status = status, headers = headers)
                } else {
                    respond(content = body, status = status, headers = headers)
                }
            },
        ),
        json,
    )

    private fun body(size: Int, fill: Char = 'x') = ByteArray(size) { fill.code.toByte() }

    private fun failureOf(read: suspend () -> Any?): Throwable? =
        runCatching { runBlocking { withTimeout(30_000) { read() } } }.exceptionOrNull()

    @Test
    fun `below-budget response passes through untouched`() {
        val api = api(body(1024))
        val element = runBlocking { api.getJson("http://localhost", "", "/session") }
        assertTrue(element.toString().isNotEmpty())
    }

    @Test
    fun `response exactly at the budget succeeds`() {
        val exact = PayloadLimits.MAX_RESPONSE_DEFAULT_BYTES.toInt()
        val api = api(body(exact))
        // 'x'*exact is lenient-JSON-parseable as a bare string primitive:
        // success here means the read completed AT the limit without failing.
        val element = runBlocking { api.getJson("http://localhost", "", "/session") }
        assertEquals(exact, element.toString().length)
    }

    @Test
    fun `over-budget response fails typed through the existing error pipeline`() {
        val over = PayloadLimits.MAX_RESPONSE_DEFAULT_BYTES.toInt() + 1
        val api = api(body(over))
        val failure = failureOf { api.getJson("http://localhost", "", "/session") }
        assertTrue(
            "expected ResponseTooLargeException, got $failure",
            failure is ResponseTooLargeException,
        )
        assertEquals(
            OpenCodeError.ResponseTooLarge(PayloadLimits.MAX_RESPONSE_DEFAULT_BYTES),
            requireNotNull(failure).toOpenCodeError(),
        )
    }

    @Test
    fun `declared content-length over budget is rejected before reading the body`() {
        val over = PayloadLimits.MAX_RESPONSE_DEFAULT_BYTES.toInt() + 1
        val api = api(
            body(over),
            declaredLength = PayloadLimits.MAX_RESPONSE_DEFAULT_BYTES + 1,
            asChannel = true,
        )
        val failure = failureOf { api.getJson("http://localhost", "", "/session") }
        assertTrue(failure is ResponseTooLargeException)
    }

    @Test
    fun `response without declared length is counted and rejected`() {
        val over = PayloadLimits.MAX_RESPONSE_DEFAULT_BYTES.toInt() + 1
        val api = api(body(over), asChannel = true)
        val failure = failureOf { api.getJson("http://localhost", "", "/session") }
        assertTrue(failure is ResponseTooLargeException)
    }

    @Test
    fun `message endpoints get the 16 MB budget and others do not`() {
        val six = 6 * 1024 * 1024
        val payload = (
            """[{"info":{"id":"m1","role":"user","sessionID":"s1"},"parts":[{"id":"p1","type":"text","text":" """ +
                "z".repeat(six) +
                """ "}]}]"""
            ).toByteArray()
        val api = api(payload)
        val messages = runBlocking { api.listMessages("http://localhost", "", "s1") }
        assertEquals(1, messages.size)

        val sessions = api(payload)
        val failure = failureOf { sessions.listSessions("http://localhost", "") }
        assertTrue(failure is ResponseTooLargeException)
    }

    @Test
    fun `small metadata endpoints get the 1 MB budget`() {
        val two = 2 * 1024 * 1024
        val failure = failureOf { api(body(two)).health("http://localhost", "") }
        assertTrue(failure is ResponseTooLargeException)
    }

    @Test
    fun `SSE streams over the default budget flow through the real engine untouched`() {
        // The guard lives in OpenCodeApi reads; the event feed consumes its
        // channel via KtorSseConnector and must never be capped. 6 MB of
        // frames over a real socket proves the exemption end-to-end.
        val framePayload = "y".repeat(150 * 1024)
        val server = SseTestServer()
        server.handler = { _, conn ->
            conn.beginSse()
            repeat(40) { conn.event("""{"type":"session.next.text.delta","properties":{"sessionID":"s1","partID":"p1","delta":"$framePayload"}}""") }
            conn.finish()
        }
        try {
            val transport = SseEventTransport(
                NetworkModule.provideHttpClient(json),
                FakeConnectionRepository(ServerConfig(server.baseUrl, "tok")),
                CoroutineScope(SupervisorJob()),
            )
            val frames = runBlocking {
                withTimeout(30_000) {
                    transport.frames(ServerConfig(server.baseUrl, "tok")).toList()
                }
            }
            assertEquals(40, frames.size)
            assertTrue(frames.first().contains(framePayload))
        } finally {
            runCatching { server.close() }
        }
    }

    @Test
    fun `budget table matches the approved limits`() {
        assertEquals(PayloadLimits.MAX_RESPONSE_MESSAGES_BYTES, budgetFor("/session/x/message"))
        assertEquals(PayloadLimits.MAX_RESPONSE_SMALL_BYTES, budgetFor("/global/health"))
        assertEquals(PayloadLimits.MAX_RESPONSE_SMALL_BYTES, budgetFor("/provider"))
        assertEquals(PayloadLimits.MAX_RESPONSE_SMALL_BYTES, budgetFor("/config"))
        assertEquals(PayloadLimits.MAX_RESPONSE_DEFAULT_BYTES, budgetFor("/session"))
        assertEquals(PayloadLimits.MAX_RESPONSE_DEFAULT_BYTES, budgetFor("/file"))
        assertEquals(PayloadLimits.MAX_RESPONSE_DEFAULT_BYTES, budgetFor("/vcs/diff"))
    }
}

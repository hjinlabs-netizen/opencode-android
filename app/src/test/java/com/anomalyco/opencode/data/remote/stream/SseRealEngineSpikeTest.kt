package com.anomalyco.opencode.data.remote.stream

import com.anomalyco.opencode.data.remote.OpenCodeHttpException
import com.anomalyco.opencode.data.remote.toOpenCodeError
import com.anomalyco.opencode.di.NetworkModule
import com.anomalyco.opencode.domain.error.OpenCodeError
import com.anomalyco.opencode.domain.model.ServerConfig
import com.anomalyco.opencode.util.FakeConnectionRepository
import com.anomalyco.opencode.util.SseTestServer
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * Sprint 1c.1 SPIKE GATE: the production SSE path — `NetworkModule`'s OkHttp
 * engine, the installed `SSE` plugin, the real `KtorSseConnector`, the
 * content-type gate, bearer wiring, keep-alive filtering and mid-stream
 * socket deaths — executed against REAL loopback sockets. The `SseConnector`
 * seam is deliberately NOT replaced.
 *
 * SPIKE VERDICTS recorded from the first run:
 *  - okhttp MockWebServer is INCOMPATIBLE with the Ktor OkHttp SSE engine
 *    (`RealEventSource` dies with `byteCount < 0: -1` on length-framed
 *    bodies); the real-socket [SseTestServer] fixture is used for both
 *    request-shape and streaming assertions, and the test-only MockWebServer
 *    dependency was dropped again.
 *  - mid-stream deaths arrive as `SSEClientException` wrapping an
 *    `IOException`, never a bare IOException (ErrorMapping now unwraps).
 *  - 401 handshakes were masked as probe-misses and re-probed every
 *    candidate (KtorSseConnector.handshakeMiss fixes the fast-fail).
 *  - the explicit `Accept: text/event-stream` is APPENDED to Ktor's default
 *    accept list rather than replacing it (benign duplication, pinned here
 *    as truth for the matrix).
 */
class SseRealEngineSpikeTest {

    private val client: HttpClient = NetworkModule.provideHttpClient(NetworkModule.provideJson())

    @After
    fun tearDown() {
        runCatching { client.close() }
    }

    private fun transport(url: String, token: String = "tok"): SseEventTransport =
        SseEventTransport(
            client,
            FakeConnectionRepository(ServerConfig(url, token)),
            CoroutineScope(SupervisorJob()),
        )

    private fun rawServer(
        handler: (SseTestServer.Request, SseTestServer.Connection) -> Unit,
    ): SseTestServer = SseTestServer().also { it.handler = handler }

    private fun collect(
        transport: SseEventTransport,
        url: String,
        token: String = "tok",
        onConnected: () -> Unit = {},
    ): List<String> = runBlocking {
        try {
            withTimeout(TIMEOUT_MS) { transport.frames(ServerConfig(url, token), onConnected).toList() }
        } catch (timeout: TimeoutCancellationException) {
            throw AssertionError("SSE operation exceeded ${TIMEOUT_MS} ms", timeout)
        }
    }

    private fun collectFailure(transport: SseEventTransport, url: String): Throwable =
        runCatching {
            runBlocking { withTimeout(TIMEOUT_MS) { transport.frames(ServerConfig(url, "tok")).toList() } }
        }.exceptionOrNull() ?: throw AssertionError("expected the stream to fail, it completed normally")

    private val idleFrame = """{"type":"session.idle","properties":{}}"""

    // ---- handshake, content-type gate, probe fall-through, memoization ----
    @Test
    fun `real engine probes SPA-html event route then completes on the fallback and memoizes it`() {
        val server = rawServer { request, conn ->
            if (request.path == "/event") {
                conn.respond(200, "text/html", "<!doctype html><html>SPA</html>")
            } else {
                conn.beginSse()
                conn.event("""{"type":"session.idle","properties":{"sessionID":"s1"}}""")
                conn.event("""{"type":"session.next.text.delta","properties":{"sessionID":"s1","partID":"p1","delta":"hey"}}""")
                conn.finish()
            }
        }
        val probe = transport(server.baseUrl)

        val frames = collect(probe, server.baseUrl)

        assertEquals(2, frames.size)
        assertTrue(frames[0].contains("session.idle"))
        assertTrue(frames[1].contains("delta"))
        assertEquals("/global/event", probe.winnerFor(server.baseUrl))
        assertEquals(listOf("/event", "/global/event"), server.requests.map { it.path })
    }

    @Test
    fun `memoized winner is reused without re-probing on the real engine`() {
        val requested = java.util.concurrent.CopyOnWriteArrayList<String>()
        val server = rawServer { request, conn ->
            requested += request.path
            conn.beginSse()
            conn.event(idleFrame)
            conn.finish()
        }
        val probe = transport(server.baseUrl)

        collect(probe, server.baseUrl) // /event answers directly and wins
        assertEquals(listOf("/event"), requested)

        collect(probe, server.baseUrl)
        collect(probe, server.baseUrl)

        // Reconnects go straight to the memoized winner: no re-probing.
        assertEquals(listOf("/event", "/event", "/event"), requested)
        assertEquals("/event", probe.winnerFor(server.baseUrl))
    }

    // ---- wire headers on the real SSE request ----
    @Test
    fun `sse request carries the Accept header and bearer auth only when a token exists`() {
        val server = rawServer { _, conn ->
            conn.beginSse()
            conn.event(idleFrame)
            conn.finish()
        }

        collect(transport(server.baseUrl, token = "secret-token"), server.baseUrl, token = "secret-token")
        collect(transport(server.baseUrl, token = ""), server.baseUrl, token = "")

        val (authed, anonymous) = server.requests
        // Spike finding: Ktor APPENDS the explicit Accept to the client's
        // defaults, so the real wire value is
        // "text/event-stream,application/json,text/event-stream". Event-stream
        // leads the list; servers honour it. Pinned as truth (cosmetic dup).
        assertTrue(
            "unexpected Accept: ${authed.header("accept")}",
            authed.header("accept")?.startsWith("text/event-stream") == true,
        )
        assertEquals("Bearer secret-token", authed.header("authorization"))
        assertTrue(
            "unexpected Accept: ${anonymous.header("accept")}",
            anonymous.header("accept")?.startsWith("text/event-stream") == true,
        )
        assertNull(anonymous.header("authorization"))
    }

    // ---- handshake proof precedes the first data frame ----
    @Test
    fun `onConnected fires at handshake while the real stream is still silent`() {
        val connectedAt = AtomicLong(-1L)
        val firstFrameAt = AtomicLong(-1L)
        val server = rawServer { _, conn ->
            conn.beginSse()
            Thread.sleep(500) // healthy-but-quiet window (keep-alives would go here)
            conn.event(idleFrame)
            conn.finish()
        }

        val frames = runBlocking {
            withTimeout(TIMEOUT_MS) {
                transport(server.baseUrl)
                    .frames(ServerConfig(server.baseUrl, "tok"), onConnected = { connectedAt.set(System.nanoTime()) })
                    .onEach { firstFrameAt.compareAndSet(-1L, System.nanoTime()) }
                    .toList()
            }
        }

        assertEquals(1, frames.size)
        assertTrue("handshake was never reported", connectedAt.get() > 0)
        val quietMillis = (firstFrameAt.get() - connectedAt.get()) / 1_000_000
        assertTrue("first frame arrived only $quietMillis ms after the handshake (expected >= 400)", quietMillis >= 400)
    }

    // ---- comment keep-alives + clean terminator ----
    @Test
    fun `comment keep-alives are filtered and a clean terminator completes the flow`() {
        val server = rawServer { _, conn ->
            conn.beginSse()
            conn.comment("ping")
            conn.comment("ping")
            conn.event(idleFrame)
            conn.finish()
        }

        val frames = collect(transport(server.baseUrl), server.baseUrl)

        assertEquals(1, frames.size) // comments produced no payload; the event did
    }

    // ---- mid-stream socket death ----
    @Test
    fun `mid-stream socket death throws and classifies as Network Connect`() {
        val server = rawServer { _, conn ->
            conn.beginSse()
            conn.event(idleFrame)
            Thread.sleep(400)
            conn.kill() // RST before the terminating chunk
        }

        val failure = collectFailure(transport(server.baseUrl), server.baseUrl)

        // Spike finding: the real engine wraps deaths (SSEClientException
        // carrying an IOException), never a bare IOException — the
        // classifier unwraps it (ErrorMapping).
        assertTrue(
            "expected an IOException-shaped death, got ${failure::class.java.name}: ${failure.message}",
            hasIoCause(failure),
        )
        val typed = failure.toOpenCodeError()
        assertTrue("expected Network, got $typed", typed is OpenCodeError.Network)
        assertEquals(OpenCodeError.NetworkKind.Connect, (typed as OpenCodeError.Network).kind)
    }

    // ---- real 401 must not degrade into a probe storm ----
    @Test
    fun `401 on the event route is an auth failure not a probe miss`() {
        val server = rawServer { _, conn -> conn.respond(401, "text/plain", "no") }

        val failure = collectFailure(transport(server.baseUrl), server.baseUrl)

        assertEquals(OpenCodeError.AuthRejected, failure.toOpenCodeError())
        assertEquals("auth must fail fast on the FIRST candidate", 1, server.requests.size)
        assertTrue("auth failures surface as OpenCodeHttpException, got $failure", failure is OpenCodeHttpException)
    }

    private fun hasIoCause(t: Throwable?): Boolean {
        var current = t
        while (current != null) {
            if (current is IOException) return true
            current = current.cause
        }
        return false
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L
    }
}

package com.anomalyco.opencode.data.remote.stream

import com.anomalyco.opencode.data.repository.ChatStreamRepositoryImpl
import com.anomalyco.opencode.di.NetworkModule
import com.anomalyco.opencode.domain.error.OpenCodeError
import com.anomalyco.opencode.domain.model.ServerConfig
import com.anomalyco.opencode.domain.model.StreamEvent
import com.anomalyco.opencode.domain.model.StreamStatus
import com.anomalyco.opencode.util.FakeConnectionRepository
import com.anomalyco.opencode.util.SseTestServer
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The SSE engine integration matrix as a platform-agnostic abstract spec:
 * every case drives the PRODUCTION stack — `NetworkModule`'s OkHttp client,
 * the real `KtorSseConnector`, [SseEventTransport] probe/memoization, the
 * [OpenCodeStreamClient] supervisor and [ChatStreamRepositoryImpl]'s
 * config-driven lifecycle — over real loopback sockets against
 * [SseTestServer]. No connector fakes anywhere.
 *
 * Concrete subclasses run the SAME assertions on each platform (Sprint 1c.3):
 *  - `SseEngineIntegrationTest` (JVM / Tier A; adds the backpressure case,
 *    deliberately JVM-only),
 *  - `SseEngineAndroidIntegrationTest` (device / Tier B; adds Dalvik-platform
 *    characteristics coverage in a separate class).
 *
 * Fixture: [SseTestServer] (sharedTest) — MockWebServer proved incompatible
 * with the Ktor OkHttp SSE engine in the 1c.1 spike.
 */
abstract class SseEngineIntegrationSpec {

    protected val json = NetworkModule.provideJson()
    protected val client: HttpClient = NetworkModule.provideHttpClient(json)
    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val servers = mutableListOf<SseTestServer>()

    protected val holdMs: Long = 30_000L
    protected val timeoutMs: Long = 15_000L

    @After
    fun tearDown() {
        servers.forEach { runCatching { it.close() } }
        scope.cancel()
        runCatching { client.close() }
    }

    // ---- wiring helpers ------------------------------------------------------

    protected fun server(
        handler: (SseTestServer.Request, SseTestServer.Connection) -> Unit,
    ): SseTestServer = SseTestServer().also {
        it.handler = handler
        servers += it
    }

    protected fun transportFor(connection: FakeConnectionRepository): SseEventTransport =
        SseEventTransport(client, connection, scope)

    protected fun clientFor(
        connection: FakeConnectionRepository,
        transport: SseEventTransport = transportFor(connection),
    ): OpenCodeStreamClient =
        OpenCodeStreamClient(transport, StreamEventDecoder(json), scope).apply { jitter = { 0.0 } }

    protected fun config(url: String, token: String = "tok") = ServerConfig(url, token)

    protected fun recordStatuses(stream: OpenCodeStreamClient): CopyOnWriteArrayList<StreamStatus> =
        CopyOnWriteArrayList<StreamStatus>().also { seen -> scope.launch { stream.status.collect { seen += it } } }

    protected fun recordEvents(
        events: Flow<StreamEvent>,
        onEvent: (StreamEvent) -> Unit = {},
    ): CopyOnWriteArrayList<StreamEvent> =
        CopyOnWriteArrayList<StreamEvent>().also { seen -> scope.launch { events.collect { seen += it; onEvent(it) } } }

    protected suspend fun awaitUntil(what: String, deadlineMs: Long = 12_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + deadlineMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(25)
        }
        throw AssertionError("timed out waiting for: $what")
    }

    protected fun errorOf(statuses: List<StreamStatus>): OpenCodeError? =
        statuses.filterIsInstance<StreamStatus.Error>().lastOrNull()?.error

    protected fun SseTestServer.Connection.holdOpen() {
        try {
            Thread.sleep(holdMs)
            finish()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Exception) {
            // Teardown closed the socket mid-hold: expected.
        }
    }

    protected fun delta(session: String, text: String) =
        """{"type":"session.next.text.delta","properties":{"sessionID":"$session","partID":"p1","delta":"$text"}}"""

    protected val idleFrame = """{"type":"session.idle","properties":{"sessionID":"s1"}}"""

    // ---- 1. initial connection ------------------------------------------------
    @Test
    fun `initial connection reports Connected at handshake and then delivers events`() {
        val server = server { _, conn ->
            conn.beginSse()
            Thread.sleep(400) // quiet window before the first payload frame
            conn.event(idleFrame)
            conn.holdOpen()
        }
        val stream = clientFor(FakeConnectionRepository(config(server.baseUrl)))
        val statuses = recordStatuses(stream)
        var connectedBeforeFirstEvent: Boolean? = null
        val events = recordEvents(stream.events) {
            if (connectedBeforeFirstEvent == null) {
                connectedBeforeFirstEvent = statuses.contains(StreamStatus.Connected)
            }
        }

        runBlocking {
            stream.start(config(server.baseUrl))
            awaitUntil("Connected") { stream.status.value == StreamStatus.Connected }
            awaitUntil("first event") { events.isNotEmpty() }
        }

        assertEquals(true, connectedBeforeFirstEvent)
        assertEquals(listOf<StreamEvent>(StreamEvent.SessionIdle("s1")), events.toList())
        assertEquals(StreamStatus.Connected, statuses.last())
    }

    // ---- 2. successful event stream -------------------------------------------
    @Test
    fun `live event stream delivers every decoded delta in order`() {
        val server = server { _, conn ->
            conn.beginSse()
            "hello".map { ch -> conn.event(delta("s1", ch.toString())) }
            conn.holdOpen()
        }
        val stream = clientFor(FakeConnectionRepository(config(server.baseUrl)))
        val events = recordEvents(stream.events)

        runBlocking {
            stream.start(config(server.baseUrl))
            awaitUntil("five deltas") { events.size == 5 }
        }

        assertEquals(
            listOf("h", "e", "l", "l", "o"),
            events.map { (it as StreamEvent.TextDelta).delta },
        )
    }

    // ---- 3. keep-alive handling ------------------------------------------------
    @Test
    fun `comment keep-alives hold the connection open without events or flap`() {
        val server = server { _, conn ->
            conn.beginSse()
            repeat(6) { conn.comment("ping"); Thread.sleep(150) }
            conn.event(idleFrame)
            conn.holdOpen()
        }
        val stream = clientFor(FakeConnectionRepository(config(server.baseUrl)))
        val events = recordEvents(stream.events)

        runBlocking {
            stream.start(config(server.baseUrl))
            awaitUntil("Connected") { stream.status.value == StreamStatus.Connected }
            awaitUntil("event after keep-alives") { events.isNotEmpty() }
            assertEquals("keep-alives must not reconnect", 1, server.requests.size)
            assertTrue("status must stay Connected", stream.status.value == StreamStatus.Connected)
        }

        assertEquals(1, events.size) // comments produced nothing at any layer
    }

    // ---- 4. server disconnect ---------------------------------------------------
    @Test
    fun `clean server close yields Error Closed then auto-reconnects`() {
        val attempts = AtomicInteger()
        val server = server { _, conn ->
            if (attempts.incrementAndGet() == 1) {
                conn.beginSse()
                conn.event(idleFrame)
                conn.finish() // polite goodbye
            } else {
                conn.beginSse()
                conn.holdOpen()
            }
        }
        val stream = clientFor(FakeConnectionRepository(config(server.baseUrl)))
        val statuses = recordStatuses(stream)

        runBlocking {
            stream.start(config(server.baseUrl))
            awaitUntil("Connected") { statuses.contains(StreamStatus.Connected) }
            awaitUntil("Closed error") {
                (errorOf(statuses) as? OpenCodeError.Network)?.kind == OpenCodeError.NetworkKind.Closed
            }
            awaitUntil("reconnected") { server.requests.size >= 2 && stream.status.value == StreamStatus.Connected }
        }

        assertEquals(listOf("/event", "/event"), server.requests.map { it.path })
    }

    // ---- 5. mid-stream interruption ---------------------------------------------
    @Test
    fun `mid-stream RST classifies as Network Connect and reconnect skips re-probing`() {
        val globalAttempts = AtomicInteger()
        val server = server { request, conn ->
            if (request.path == "/event") {
                conn.respond(404, "text/plain", "no") // memoization phase-1 miss
            } else {
                if (globalAttempts.incrementAndGet() == 1) {
                    conn.beginSse()
                    conn.event(idleFrame)
                    Thread.sleep(300)
                    conn.kill() // RST mid-stream
                } else {
                    conn.beginSse()
                    conn.holdOpen()
                }
            }
        }
        val stream = clientFor(FakeConnectionRepository(config(server.baseUrl)))
        val statuses = recordStatuses(stream)

        runBlocking {
            stream.start(config(server.baseUrl))
            awaitUntil("first Connected") { statuses.contains(StreamStatus.Connected) }
            awaitUntil("Connect error after RST") {
                (errorOf(statuses) as? OpenCodeError.Network)?.kind == OpenCodeError.NetworkKind.Connect
            }
            awaitUntil("reconnected to winner") {
                server.requests.size >= 3 && stream.status.value == StreamStatus.Connected
            }
        }

        val paths = server.requests.map { it.path }
        assertEquals(listOf("/event", "/global/event", "/global/event"), paths)
    }

    // ---- 6. automatic reconnect ---------------------------------------------------
    @Test
    fun `transient handshake failures recover through the backoff ladder`() {
        val attempts = AtomicInteger()
        val server = server { _, conn ->
            if (attempts.incrementAndGet() <= 2) {
                conn.kill() // die before any header — pure transport failure
            } else {
                conn.beginSse()
                conn.event(idleFrame)
                conn.holdOpen()
            }
        }
        val stream = clientFor(FakeConnectionRepository(config(server.baseUrl)))
        val statuses = recordStatuses(stream)
        val events = recordEvents(stream.events)

        runBlocking {
            stream.start(config(server.baseUrl))
            awaitUntil("eventual success") {
                server.requests.size >= 3 && stream.status.value == StreamStatus.Connected
            }
            awaitUntil("post-reconnect event") { events.isNotEmpty() }
        }

        val errors = statuses.filterIsInstance<StreamStatus.Error>()
        assertTrue("expected at least two failed attempts", errors.size >= 2)
        assertTrue(
            "transport deaths must classify as Network, got ${errors.map { it.error }}",
            errors.all { (it.error as? OpenCodeError.Network)?.kind == OpenCodeError.NetworkKind.Connect },
        )
    }

    // ---- 7. authentication failure --------------------------------------------------
    @Test
    fun `auth rejection fails fast on the first candidate and types as AuthRejected`() {
        val server = server { _, conn -> conn.respond(401, "text/plain", "no") }
        val stream = clientFor(FakeConnectionRepository(config(server.baseUrl)))
        val statuses = recordStatuses(stream)

        runBlocking {
            stream.start(config(server.baseUrl))
            awaitUntil("AuthRejected") { errorOf(statuses) == OpenCodeError.AuthRejected }
            delay(1_500) // allow at least one backed-off retry
        }

        val paths = server.requests.map { it.path }
        assertTrue("expected at least two attempts", paths.size >= 2)
        assertTrue(
            "every attempt must hit ONLY the first candidate (no probe storm): $paths",
            paths.all { it == "/event" },
        )
        assertEquals("tok", server.requests.first().header("authorization")?.removePrefix("Bearer "))
    }

    // ---- 8. event ordering --------------------------------------------------------------
    @Test
    fun `interleaved sessions and batch-array frames preserve exact wire order`() {
        val batch = """
            [{"type":"session.next.text.delta","properties":{"sessionID":"C","partID":"p1","delta":"C1"}},
             {"type":"session.next.text.delta","properties":{"sessionID":"B","partID":"p1","delta":"B2"}}]
        """.trimIndent().replace("\n", "")
        val server = server { _, conn ->
            conn.beginSse()
            conn.event(delta("A", "A1"))
            conn.event(delta("B", "B1"))
            conn.event(delta("A", "A2"))
            conn.event(batch)
            conn.event(delta("A", "A3"))
            conn.holdOpen()
        }
        val stream = clientFor(FakeConnectionRepository(config(server.baseUrl)))
        val events = recordEvents(stream.events)

        runBlocking {
            stream.start(config(server.baseUrl))
            awaitUntil("six events") { events.size == 6 }
        }

        assertEquals(
            listOf("A1", "B1", "A2", "C1", "B2", "A3"),
            events.filterIsInstance<StreamEvent.TextDelta>().map { it.delta },
        )
    }

    // ---- 9. config change behavior --------------------------------------------------------
    @Test
    fun `stream follows the server config - starts on set follows swaps and stops on clear`() {
        val connection = FakeConnectionRepository(server = null)
        val transport = transportFor(connection)
        val stream = clientFor(connection, transport)
        val repository = ChatStreamRepositoryImpl(stream, connection, scope)
        val statuses = CopyOnWriteArrayList<StreamStatus>()
        scope.launch { repository.status.collect { statuses += it } }
        val events = recordEvents(repository.events)

        val serverA = server { _, conn -> conn.beginSse(); conn.event(delta("A", "fromA")); conn.holdOpen() }
        val serverB = server { _, conn -> conn.beginSse(); conn.event(delta("B", "fromB")); conn.holdOpen() }

        runBlocking {
            connection.configFlow.value = config(serverA.baseUrl)
            awaitUntil("stream A connected") { stream.status.value == StreamStatus.Connected }
            awaitUntil("event from A") { events.any { (it as? StreamEvent.TextDelta)?.delta == "fromA" } }
            val aTraffic = serverA.requests.size

            connection.configFlow.value = config(serverB.baseUrl)
            awaitUntil("event from B") { events.any { (it as? StreamEvent.TextDelta)?.delta == "fromB" } }
            delay(300)
            assertEquals("old server must receive no traffic after the swap", aTraffic, serverA.requests.size)
            assertTrue("new server got the reconnect", serverB.requests.isNotEmpty())

            connection.configFlow.value = null
            awaitUntil("stream stopped") { stream.status.value == StreamStatus.Disconnected }
        }
        assertTrue(statuses.isNotEmpty())
    }

    // ---- 10. memoized endpoint behavior -----------------------------------------------------
    @Test
    fun `winner memoization skips probes on reconnect and demotes a moved route`() {
        var globalBroken = false
        val server = server { request, conn ->
            val path = request.path
            val broken = (path == "/global/event" && globalBroken) || (path == "/event" && !globalBroken)
            if (broken) {
                conn.respond(200, "text/html", "<!doctype html>SPA") // probe miss
            } else {
                conn.beginSse()
                conn.event(idleFrame)
                conn.finish()
            }
        }
        val transport = transportFor(FakeConnectionRepository(config(server.baseUrl)))
        val cfg = config(server.baseUrl)

        suspend fun drain(): List<String> = withTimeout(timeoutMs) { transport.frames(cfg).toList() }

        runBlocking {
            assertEquals(1, drain().size) // phase 1: /event misses, /global/event wins
            assertEquals("/global/event", transport.winnerFor(server.baseUrl))
            assertEquals(listOf("/event", "/global/event"), server.requests.map { it.path })
            val afterFirst = server.requests.size

            drain() // phase 2: straight to the winner, zero re-probing
            assertEquals(afterFirst + 1, server.requests.size)

            globalBroken = true // the server MOVES the route back to /event
            drain() // phase 3: winner 404s -> demoted, re-probed, /event wins
            assertEquals("/event", transport.winnerFor(server.baseUrl))

            drain() // phase 4: new winner probed first
            assertTrue(server.requests.last().path == "/event")
        }
    }
}

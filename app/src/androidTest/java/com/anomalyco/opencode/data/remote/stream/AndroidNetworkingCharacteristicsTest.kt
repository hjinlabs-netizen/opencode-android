package com.anomalyco.opencode.data.remote.stream

import com.anomalyco.opencode.data.remote.toOpenCodeError
import com.anomalyco.opencode.di.NetworkModule
import com.anomalyco.opencode.domain.error.OpenCodeError
import com.anomalyco.opencode.domain.model.ServerConfig
import com.anomalyco.opencode.util.FakeConnectionRepository
import com.anomalyco.opencode.util.SseTestServer
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * Android-platform characteristics of the SSE path (Sprint 1c.3) — the
 * facts Tier A (JVM) cannot prove because the Android policy layer does not
 * exist there:
 *
 *  1. loopback addressing works from the app process and a CLEARTEXT HTTP
 *     SSE handshake succeeds under `network_security_config.xml` (the exact
 *     production LAN scenario),
 *  2. the request rides the ANDROID platform network stack (Dalvik
 *     user-agent) with the `text/event-stream` accept header first,
 *  3. a mid-stream dead socket is fully cleaned up: the bare transport
 *     leaves no orphan connections or pool poisoning behind, and a
 *     subsequent connect succeeds.
 *
 * Snake_case names: DEX < 040 (minSdk 26) forbids spaces in method names
 * (discovered by the 1c.3 dexing gate).
 */
class AndroidNetworkingCharacteristicsTest {

    private val client: HttpClient = NetworkModule.provideHttpClient(NetworkModule.provideJson())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val servers = mutableListOf<SseTestServer>()

    @After
    fun tearDown() {
        servers.forEach { runCatching { it.close() } }
        scope.cancel()
        runCatching { client.close() }
    }

    private fun server(
        handler: (SseTestServer.Request, SseTestServer.Connection) -> Unit,
    ): SseTestServer = SseTestServer().also {
        it.handler = handler
        servers += it
    }

    private fun transport(url: String): SseEventTransport =
        SseEventTransport(client, FakeConnectionRepository(ServerConfig(url, "tok")), scope)

    private fun drain(transport: SseEventTransport, url: String): List<String> =
        runBlocking { withTimeout(20_000) { transport.frames(ServerConfig(url, "tok")).toList() } }

    private val idleFrame = """{"type":"session.idle","properties":{"sessionID":"s1"}}"""

    @Test
    fun platform_loopback_resolves_and_cleartext_sse_handshake_succeeds() {
        assertTrue(
            "127.0.0.1 must resolve as loopback on the platform",
            InetAddress.getByName("127.0.0.1").isLoopbackAddress,
        )
        // Cleartext proof: base-config cleartextTrafficPermitted must let the
        // REAL production LAN scenario through. If policy blocked it, OkHttp
        // would throw "CLEARTEXT communication ... not permitted" here.
        val server = server { _, conn ->
            conn.beginSse()
            conn.event(idleFrame)
            conn.finish()
        }

        val frames = drain(transport(server.baseUrl), server.baseUrl)

        assertEquals(1, frames.size)
        assertTrue(frames.single().contains("session.idle"))
    }

    @Test
    fun platform_requests_ride_dalvik_stack_with_event_stream_accept() {
        val server = server { _, conn ->
            conn.beginSse()
            conn.event(idleFrame)
            conn.finish()
        }

        drain(transport(server.baseUrl), server.baseUrl)

        val request = server.requests.single()
        val userAgent = request.header("user-agent").orEmpty()
        assertTrue(
            "expected a Dalvik user-agent on device, got: $userAgent",
            userAgent.contains("Dalvik", ignoreCase = true),
        )
        assertTrue(
            "expected text/event-stream first in Accept, got: ${request.header("accept")}",
            request.header("accept")?.startsWith("text/event-stream") == true,
        )
        assertNotNull("running under the Android ART/Dalvik VM", System.getProperty("java.vm.name"))
    }

    @Test
    fun platform_dead_socket_leaves_no_orphan_and_next_connect_succeeds() {
        var first = true
        val server = server { _, conn ->
            conn.beginSse()
            conn.event(idleFrame)
            if (first) {
                first = false
                Thread.sleep(300)
                conn.kill() // RST with the client still attached
            } else {
                conn.finish()
            }
        }
        val transport = transport(server.baseUrl)

        val failure = runCatching { drain(transport, server.baseUrl) }.exceptionOrNull()
        assertNotNull("the killed stream must fail", failure)
        assertEquals(
            OpenCodeError.NetworkKind.Connect,
            (requireNotNull(failure).toOpenCodeError() as? OpenCodeError.Network)?.kind,
        )

        runBlocking { delay(1_500) }
        assertEquals("a dead transport connection must not spawn orphan attempts", 1, server.requests.size)

        // Pool not poisoned: a fresh collect over the same client works.
        val frames = drain(transport, server.baseUrl)
        assertEquals(1, frames.size)
        assertEquals(2, server.requests.size)
    }
}

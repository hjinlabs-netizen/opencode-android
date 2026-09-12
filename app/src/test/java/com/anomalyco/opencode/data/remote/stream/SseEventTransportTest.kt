package com.anomalyco.opencode.data.remote.stream

import com.anomalyco.opencode.data.remote.OpenCodeHttpException
import com.anomalyco.opencode.data.remote.UnsupportedResponseException
import com.anomalyco.opencode.domain.model.ServerConfig
import com.anomalyco.opencode.util.FakeConnectionRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

/**
 * Candidate probing + winner memoization of the event stream (Sprint A
 * P0-2), driven through the [SseConnector] seam so no real SSE engine is
 * involved: probe misses fall through, the handshake path is memoized per
 * base URL, and post-handshake drops are never re-probed.
 */
class SseEventTransportTest {

    private val config = ServerConfig("http://10.0.0.5:4096", "tok")
    private val base = "http://10.0.0.5:4096"
    private val frame = """{"type":"session.idle","properties":{"sessionID":"s1"}}"""

    private val opened = mutableListOf<String>()

    private fun transport(
        connection: FakeConnectionRepository = FakeConnectionRepository(),
        handler: (path: String, onConnected: () -> Unit) -> Flow<String>,
    ): SseEventTransport = SseEventTransport(
        HttpClient(MockEngine { _: HttpRequestData -> respond("", HttpStatusCode.OK) }),
        connection,
        CoroutineScope(UnconfinedTestDispatcher()),
    ).apply {
        connector = SseConnector { _, path, onConnected ->
            opened += path
            handler(path, onConnected)
        }
    }

    private fun collect(transport: SseEventTransport): List<String> = runBlocking {
        transport.frames(config) {}.toList()
    }

    private fun miss(path: String) = UnsupportedResponseException(path, "HTTP 200 text/html")

    @Test
    fun `probes candidates until one handshakes and memoizes the winner`() = runTest {
        val transport = transport { path, onConnected ->
            if (path == "/event") flow { throw miss(path) }
            else flow { onConnected(); emit(frame) }
        }

        val frames = transport.frames(config) {}.toList()

        assertEquals(listOf(frame), frames)
        assertEquals(listOf("/event", "/global/event"), opened)
        assertEquals("/global/event", transport.winnerFor(base))
    }

    @Test
    fun `reconnect goes straight to the memoized winner`() = runTest {
        val transport = transport { _, onConnected ->
            flow { onConnected(); emit(frame) }
        }
        transport.frames(config) {}.toList() // /event wins immediately
        assertEquals("/event", transport.winnerFor(base))
        opened.clear()

        transport.frames(config) {}.toList()
        transport.frames(config) {}.toList()

        assertEquals(listOf("/event", "/event"), opened)
    }

    @Test
    fun `server config change clears the memoized winners`() = runTest {
        val connection = FakeConnectionRepository(ServerConfig(base, "tok"))
        val transport = transport(connection = connection) { _, onConnected ->
            flow { onConnected(); emit(frame) }
        }
        transport.frames(config) {}.toList()
        assertEquals("/event", transport.winnerFor(base))

        // Swap the server configuration (URL/token change): the memoized
        // event path must be dropped so the new deployment is re-probed.
        connection.configFlow.value = ServerConfig("http://10.0.0.6:4096", "tok2")

        assertNull("winners must be cleared on config change", transport.winnerFor(base))
    }

    @Test
    fun `stale winner is demoted and re-probed when the server moves the route`() = runTest {
        var failFirst = false
        val transport = transport { path, onConnected ->
            flow {
                if (path == "/event" && failFirst) throw miss(path)
                onConnected()
                emit(frame)
            }
        }
        transport.frames(config) {}.toList() // winner = /event
        failFirst = true
        opened.clear()

        transport.frames(config) {}.toList()

        assertEquals(listOf("/event", "/global/event"), opened)
        assertEquals("/global/event", transport.winnerFor(base))
    }

    @Test
    fun `drop after handshake is a connection error, not a probe miss`() {
        val transport = transport { _, onConnected ->
            flow {
                onConnected()
                emit(frame)
                throw IOException("wifi died")
            }
        }

        assertThrows(IOException::class.java) { collect(transport) }
        assertEquals(listOf("/event"), opened)
    }

    @Test
    fun `hard auth error aborts probing immediately`() {
        val transport = transport { _, _ ->
            flow { throw OpenCodeHttpException(401, "") }
        }

        assertThrows(OpenCodeHttpException::class.java) { collect(transport) }
        assertEquals(listOf("/event"), opened)
    }

    @Test
    fun `all candidates missing surfaces the last probe error`() {
        val transport = transport { path, _ -> flow { throw miss(path) } }

        assertThrows(UnsupportedResponseException::class.java) { collect(transport) }
        assertEquals(listOf("/event", "/global/event"), opened)
    }
}

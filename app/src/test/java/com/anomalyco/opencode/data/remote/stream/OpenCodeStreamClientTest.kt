package com.anomalyco.opencode.data.remote.stream

import app.cash.turbine.test
import com.anomalyco.opencode.domain.model.ServerConfig
import com.anomalyco.opencode.domain.model.StreamEvent
import com.anomalyco.opencode.domain.model.StreamStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Behaviour of the supervised connection loop under a *virtual clock*:
 * transient stream failures retry with exponential backoff, a healthy
 * connection resets the ladder, `start` is idempotent per server, and an
 * explicit `stop` sticks.
 */
class OpenCodeStreamClientTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private fun decoder() = StreamEventDecoder(json)

    private val config = ServerConfig("http://10.0.0.5:4096", "tok")

    private val idleFrame = """{"type":"session.idle","properties":{"sessionID":"s1"}}"""

    private class FailTwiceThenStream(private val failures: Int) : EventTransport {
        var calls = 0
            private set
        val seenConfigs = mutableListOf<ServerConfig>()

        override fun frames(config: ServerConfig, onConnected: () -> Unit): Flow<String> = flow {
            seenConfigs += config
            calls++
            if (calls <= failures) throw IOException("drop-$calls")
            onConnected()
            emit(idleFrame())
            awaitCancellation()
        }

        private companion object {
            fun idleFrame() = """{"type":"session.idle","properties":{"sessionID":"s1"}}"""
        }
    }

    private fun clientFor(transport: EventTransport, scope: CoroutineScope) =
        OpenCodeStreamClient(transport, decoder(), scope).apply { jitter = { 0.0 } }

    @Test
    fun `reconnects with growing backoff and delivers events once healthy`() = runTest {
        val transport = FailTwiceThenStream(failures = 2)
        val client = clientFor(transport, backgroundScope)

        client.events.test {
            client.start(config)
            runCurrent()
            assertEquals(1, transport.calls)
            assertTrue(client.status.value is StreamStatus.Error)

            advanceTimeBy(1_000) // 1s base delay after first failure
            runCurrent()
            assertEquals(2, transport.calls)
            assertTrue(client.status.value is StreamStatus.Error)

            advanceTimeBy(2_000) // doubled
            runCurrent()
            assertEquals(3, transport.calls)
            assertEquals(StreamEvent.SessionIdle("s1"), awaitItem())
            assertEquals(StreamStatus.Connected, client.status.value)

            client.stop()
            assertEquals(StreamStatus.Disconnected, client.status.value)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a healthy connection resets the backoff ladder`() = runTest {
        // fail -> healthy-but-closed -> fail again: the third attempt must
        // arrive after the *base* 1s, not the doubled 2s.
        val transport = object : EventTransport {
            var calls = 0
                private set
            override fun frames(config: ServerConfig, onConnected: () -> Unit): Flow<String> = flow {
                calls++
                when (calls) {
                    1 -> throw IOException("first drop")
                    // handshake, emit one frame, then the server closes normally
                    2 -> {
                        onConnected()
                        emit("""{"type":"session.idle","properties":{"sessionID":"s"}}""")
                    }
                    else -> {
                        onConnected()
                        awaitCancellation()
                    }
                }
            }
        }
        val client = clientFor(transport, backgroundScope)

        client.start(config)
        runCurrent()
        assertEquals(1, transport.calls)

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, transport.calls)

        advanceTimeBy(1_000) // reset to base thanks to the healthy handshake
        runCurrent()
        assertEquals(3, transport.calls)
        assertEquals(StreamStatus.Connected, client.status.value)

        client.stop()
    }

    @Test
    fun `start is idempotent per normalized server and restarts on change`() = runTest {
        val transport = object : EventTransport {
            var calls = 0
                private set
            val seenConfigs = mutableListOf<ServerConfig>()
            override fun frames(config: ServerConfig, onConnected: () -> Unit): Flow<String> = flow {
                seenConfigs += config
                calls++
                onConnected()
                emit("""{"type":"session.idle","properties":{"sessionID":"s"}}""")
                awaitCancellation()
            }
        }
        val client = clientFor(transport, backgroundScope)

        client.start(ServerConfig("http://x:1/", "a"))
        client.start(ServerConfig("http://x:1", "a")) // same normalized config
        runCurrent()
        assertEquals(1, transport.calls)
        assertEquals("http://x:1", transport.seenConfigs.single().baseUrl)

        client.start(ServerConfig("http://y:2", "a")) // different server
        runCurrent()
        assertEquals(2, transport.calls)

        client.stop()
    }

    @Test
    fun `stop prevents further reconnect attempts`() = runTest {
        val transport = FailTwiceThenStream(failures = 10)
        val client = clientFor(transport, backgroundScope)

        client.start(config)
        runCurrent()
        assertEquals(1, transport.calls)

        client.stop()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, transport.calls)
        assertEquals(StreamStatus.Disconnected, client.status.value)
    }

    @Test
    fun `stop returns only after the supervisor coroutine has terminated`() = runTest {
        // P2-4: stop() must await full termination (cancel + join), not just
        // fire-and-forget cancel(). The transport's `finally` (which runs when
        // the supervisor coroutine unwinds) must have executed by the time
        // stop() returns — impossible if stop() only called cancel().
        val transport = object : EventTransport {
            var active = 0
            override fun frames(config: ServerConfig, onConnected: () -> Unit): Flow<String> = flow {
                active++
                onConnected()
                try {
                    awaitCancellation()
                } finally {
                    active--
                }
            }
        }
        val client = clientFor(transport, backgroundScope)

        client.start(config)
        runCurrent()
        assertEquals(1, transport.active)
        val job = client.activeConnectionJob
        assertNotNull(job)
        assertTrue(job!!.isActive)

        client.stop()

        assertTrue("supervisor job must be completed once stop() returns", job.isCompleted)
        assertNull("no owned job remains after stop()", client.activeConnectionJob)
        assertEquals("teardown finally must have run during stop()", 0, transport.active)
        assertEquals(StreamStatus.Disconnected, client.status.value)
    }

    @Test
    fun `reconnect awaits the previous supervisor before launching a new one`() = runTest {
        // P2-4: reconnect() must not overlap an old and a new supervisor. The
        // previous job must be fully terminated (and its transport unwound)
        // the instant reconnect() returns, before the replacement runs.
        val transport = object : EventTransport {
            var active = 0
            var maxActive = 0
            override fun frames(config: ServerConfig, onConnected: () -> Unit): Flow<String> = flow {
                active++
                if (active > maxActive) maxActive = active
                onConnected()
                try {
                    awaitCancellation()
                } finally {
                    active--
                }
            }
        }
        val client = clientFor(transport, backgroundScope)

        client.start(config)
        runCurrent()
        val old = client.activeConnectionJob
        assertNotNull(old)
        assertEquals(1, transport.active)

        client.reconnect()

        assertTrue("previous supervisor must be joined before reconnect() returns", old!!.isCompleted)
        assertEquals("previous transport must be unwound before the new one starts", 0, transport.active)
        val replacement = client.activeConnectionJob
        assertNotNull(replacement)
        assertNotSame(old, replacement)

        runCurrent()
        assertEquals("exactly one supervisor active after the replacement starts", 1, transport.active)
        assertEquals("old and new supervisors must never be active simultaneously", 1, transport.maxActive)

        client.stop()
    }

    @Test
    fun `handshake alone reaches Connected before any data frame arrives`() = runTest {
        // Regression: healthy-but-quiet streams (comment-only keep-alives)
        // must report CONNECTED, not stall in CONNECTING and flap to ERROR.
        val transport = object : EventTransport {
            override fun frames(config: ServerConfig, onConnected: () -> Unit): Flow<String> = flow {
                onConnected()
                awaitCancellation() // 200 accepted; zero payload frames ever
            }
        }
        val client = clientFor(transport, backgroundScope)

        client.start(config)
        runCurrent()
        assertEquals(StreamStatus.Connected, client.status.value)

        client.stop()
    }

    @Test
    fun `keep-alive and unknown frames do not reconnect or flap status`() = runTest {
        var calls = 0
        val transport = object : EventTransport {
            override fun frames(config: ServerConfig, onConnected: () -> Unit): Flow<String> = flow {
                calls++
                onConnected()
                // Heartbeat-style frames the decoder maps to Unknown.
                emit("""{"type":"ping"}""")
                emit("""{"type":"server.heartbeat","properties":{}}""")
                awaitCancellation()
            }
        }
        val client = clientFor(transport, backgroundScope)
        client.start(config)

        // Long virtual-time run: no data events, only keep-alives.
        advanceTimeBy(5 * 60_000)
        runCurrent()

        assertEquals(1, calls)
        assertEquals(StreamStatus.Connected, client.status.value)
        client.stop()
    }

    @Test
    fun `backoff ladder doubles, caps at 30s and jitters up to 25 percent`() {
        assertEquals(1_000L, OpenCodeStreamClient.computeBackoffDelay(0, 0.0))
        assertEquals(2_000L, OpenCodeStreamClient.computeBackoffDelay(1, 0.0))
        assertEquals(4_000L, OpenCodeStreamClient.computeBackoffDelay(2, 0.0))
        assertEquals(8_000L, OpenCodeStreamClient.computeBackoffDelay(3, 0.0))
        assertEquals(16_000L, OpenCodeStreamClient.computeBackoffDelay(4, 0.0))
        assertEquals(30_000L, OpenCodeStreamClient.computeBackoffDelay(5, 0.0))
        assertEquals(30_000L, OpenCodeStreamClient.computeBackoffDelay(99, 0.0))

        assertEquals(1_250L, OpenCodeStreamClient.computeBackoffDelay(0, 1.0))
        assertEquals(1_100L, OpenCodeStreamClient.computeBackoffDelay(0, 0.4))
        assertEquals(1_000L, OpenCodeStreamClient.computeBackoffDelay(0, -0.5)) // clamped
    }
}

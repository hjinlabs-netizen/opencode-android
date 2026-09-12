package com.anomalyco.opencode.data.remote.stream

import com.anomalyco.opencode.domain.model.StreamEvent
import com.anomalyco.opencode.util.FakeConnectionRepository
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tier A (JVM) runner of the shared [SseEngineIntegrationSpec] matrix.
 * The ten portable cases live in the spec (assertions shared verbatim with
 * the device tier, 1c.3); only the backpressure case stays JVM-only — the
 * slow-subscriber sleep × 500 frames measures the SharedFlow contract, not
 * the platform, and is CPU-noise-sensitive on constrained emulators.
 */
class SseEngineIntegrationTest : SseEngineIntegrationSpec() {

    // ---- backpressure (JVM-only by design) --------------------------------------
    @Test
    fun `burst of 500 frames against a slow subscriber arrives complete and ordered`() {
        val attempts = AtomicInteger()
        val server = server { _, conn ->
            conn.beginSse()
            if (attempts.incrementAndGet() == 1) {
                repeat(500) { conn.event(delta("s1", "d$it")) }
                conn.finish()
            } else {
                conn.holdOpen()
            }
        }
        val stream = clientFor(FakeConnectionRepository(config(server.baseUrl)))
        val events = recordEvents(stream.events) { Thread.sleep(1) } // slow subscriber

        runBlocking {
            stream.start(config(server.baseUrl))
            awaitUntil("all 500 deltas", deadlineMs = 20_000) { events.size == 500 }
        }

        val sequence = events.map { Regex("d(\\d+)").find((it as StreamEvent.TextDelta).delta)!!.groupValues[1].toInt() }
        org.junit.Assert.assertEquals((0 until 500).toList(), sequence)
    }
}

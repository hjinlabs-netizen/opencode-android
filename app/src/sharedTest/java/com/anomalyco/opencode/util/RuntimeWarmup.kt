package com.anomalyco.opencode.util

import com.anomalyco.opencode.data.remote.stream.SseEventTransport
import com.anomalyco.opencode.domain.model.ServerConfig
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One-time pre-test soak of the REAL production request pipeline (Ktor
 * client + engine + SSE plugin chain + transport) against the loopback
 * [SseTestServer].
 *
 * Why this exists: the first CI device runs proved that on a cold emulator
 * ART spends 130-260 ms verifying EACH Ktor class (hundreds of classes), so
 * the first tests to touch the stack burn 10-30 s of pure verification
 * mid-assertion and blow their budgets while every later test passes
 * (measured: identical spec cases at 0.3 s vs 30 s depending on run order;
 * zero connection errors in the device logcat). The soak moves that cost
 * into a non-asserting warm-up: once per process, before any timing
 * budget matters. Best-effort by design — a warm-up that fails to complete
 * in its budget still leaves the runtime strictly warmer than before, and
 * the real assertions then run under their normal (device-widened) budgets.
 */
object RuntimeWarmup {

    private val done = AtomicBoolean(false)

    suspend fun soakSseStackOnce(client: HttpClient, scope: CoroutineScope, budgetMs: Long) {
        if (!done.compareAndSet(false, true)) return
        val server = SseTestServer()
        try {
            server.handler = { _, conn ->
                conn.beginSse()
                conn.event("""{"type":"session.idle","properties":{"sessionID":"warmup"}}""")
                conn.finish()
            }
            val config = ServerConfig(server.baseUrl, "warmup-token")
            val transport = SseEventTransport(client, FakeConnectionRepository(config), scope)
            runCatching {
                runBlocking { withTimeout(budgetMs) { transport.frames(config).toList() } }
            }
        } finally {
            runCatching { server.close() }
        }
    }
}

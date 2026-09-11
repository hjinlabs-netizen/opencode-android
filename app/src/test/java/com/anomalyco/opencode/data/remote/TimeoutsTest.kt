package com.anomalyco.opencode.data.remote

import io.ktor.client.plugins.HttpTimeoutConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Pins the timeout semantics behind two device failures:
 *  - `sendPrompt` aborted at the client's 20s default even though the
 *    OpenCode long poll resolves at end of turn;
 *  - the SSE stream never connected because the per-request override tried
 *    to use `0` as "infinite", which HttpTimeoutConfig rejects outright.
 * [infiniteTimeouts] is the single block both transports apply.
 */
class TimeoutsTest {

    @Test
    fun `infiniteTimeouts sets the canonical sentinel without throwing`() {
        val config = HttpTimeoutConfig().apply(infiniteTimeouts)

        assertEquals(HttpTimeoutConfig.INFINITE_TIMEOUT_MS, config.requestTimeoutMillis)
        assertEquals(HttpTimeoutConfig.INFINITE_TIMEOUT_MS, config.socketTimeoutMillis)
        // Connect timeout untouched → inherits the sane client-level default.
        assertNull(config.connectTimeoutMillis)
    }

    @Test
    fun `zero is NOT the infinite sentinel and rejects the request builder`() {
        // Documents WHY `0L` is forbidden: it throws IllegalArgumentException
        // at request-build time (the original SSE transport died here every
        // reconnect, keeping the top bar stuck on "Koptu").
        assertThrows(IllegalArgumentException::class.java) {
            HttpTimeoutConfig().apply { requestTimeoutMillis = 0L }
        }
        assertThrows(IllegalArgumentException::class.java) {
            HttpTimeoutConfig().apply { socketTimeoutMillis = 0L }
        }
    }
}

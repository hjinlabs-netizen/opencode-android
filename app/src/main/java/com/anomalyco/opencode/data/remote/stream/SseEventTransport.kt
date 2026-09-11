package com.anomalyco.opencode.data.remote.stream

import com.anomalyco.opencode.data.remote.infiniteTimeouts
import com.anomalyco.opencode.domain.model.ServerConfig
import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.sseSession
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SSE ([text/event-stream]) implementation of [EventTransport] hitting
 * `GET /event` — the protocol the OpenCode server actually speaks.
 *
 * Long-lived stream caveat: the shared client's global HttpTimeout would kill
 * an idle SSE connection, so this request opts out of request/socket timeouts
 * (`HttpTimeoutConfig.INFINITE_TIMEOUT_MS`) while keeping the connect
 * timeout. Server keep-alive
 * comments (`:` frames) carry no `data` and are filtered out here.
 */
@Singleton
class SseEventTransport @Inject constructor(
    private val client: HttpClient,
) : EventTransport {

    override fun frames(
        config: ServerConfig,
        onConnected: () -> Unit,
    ): Flow<String> = flow {
        val session = client.sseSession {
            url(config.normalizedUrl + EVENT_PATH)
            header(HttpHeaders.Accept, "text/event-stream")
            if (config.token.isNotBlank()) {
                header(HttpHeaders.Authorization, "Bearer ${config.token}")
            }
            // Unlimited total request & socket inactivity timeouts:
            // the event stream is expected to stay open indefinitely
            // (HttpTimeoutConfig rejects 0; infinite is its MAX_VALUE
            // sentinel — see data/remote/Timeouts.kt).
            timeout(infiniteTimeouts)
        }
        // `sseSession` returns once the server accepted the request with a
        // 2xx response — the SSE handshake. Flip the supervisor to Connected
        // now; waiting for the first data frame would misreport healthy but
        // idle (comment keep-alive only) streams.
        onConnected()
        try {
            session.incoming.collect { event ->
                val data = event.data?.trim()
                if (!data.isNullOrEmpty()) emit(data)
            }
        } finally {
            // Releases the underlying HTTP call if the collector goes away.
            session.cancel()
        }
    }

    private companion object {
        const val EVENT_PATH = "/event"
    }
}

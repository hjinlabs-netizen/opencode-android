package com.anomalyco.opencode.data.remote.stream

import com.anomalyco.opencode.data.BoundedCache
import com.anomalyco.opencode.data.PayloadLimits
import com.anomalyco.opencode.data.remote.OpenCodeHttpException
import com.anomalyco.opencode.data.remote.UnsupportedResponseException
import com.anomalyco.opencode.data.remote.infiniteTimeouts
import com.anomalyco.opencode.di.ApplicationScope
import com.anomalyco.opencode.domain.model.ServerConfig
import com.anomalyco.opencode.domain.repository.ConnectionRepository
import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.SSEClientException
import io.ktor.client.plugins.sse.sseSession
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Seam over "open one candidate SSE connection". Returns a cold flow of raw
 * frames; handshake problems MUST surface as [UnsupportedResponseException]
 * (or another probe-miss error) during collection so the transport can fall
 * through to the next candidate. [onConnected] fires once the server
 * accepted the stream (HTTP 200 + event-stream handshake).
 */
fun interface SseConnector {
    suspend fun open(
        config: ServerConfig,
        path: String,
        onConnected: () -> Unit,
    ): Flow<String>
}

/**
 * SSE ([text/event-stream]) implementation of [EventTransport].
 *
 * Like the file endpoints, the event route differs across OpenCode builds
 * (`/event` vs `/global/event`), and unknown routes answer 200 with the SPA
 * `index.html`. This transport therefore PROBES the candidate list and
 * MEMOIZES the first path that handshakes, per server base URL — reconnects
 * go straight to the winner with zero probe latency.
 *
 * Long-lived stream caveat: the shared client's global HttpTimeout would
 * kill an idle SSE connection, so requests opt out of request/socket
 * timeouts via [infiniteTimeouts] while keeping the connect timeout.
 * Comment/keep-alive frames carry no `data` and are filtered out here.
 */
@Singleton
class SseEventTransport @Inject constructor(
    private val client: HttpClient,
    connectionRepository: ConnectionRepository,
    @ApplicationScope scope: CoroutineScope,
) : EventTransport {

    /** Test seam (and default wiring): how a candidate path is opened. */
    internal var connector: SseConnector = KtorSseConnector(client)

    /**
     * Winning event path per base URL; survives normal reconnects (memoized
     * winner is probed first) but is bounded via LRU at
     * [PayloadLimits.MAX_TRACKED_SERVERS] and cleared whenever the active
     * server configuration changes — symmetric with
     * `FileRepositoryImpl`'s endpoint probe cache (Sprint 1b).
     */
    private val winners = BoundedCache<String, String>(PayloadLimits.MAX_TRACKED_SERVERS)

    init {
        scope.launch {
            connectionRepository.config
                .distinctUntilChanged()
                .collect { winners.clear() }
        }
    }

    override fun frames(config: ServerConfig, onConnected: () -> Unit): Flow<String> = flow {
        val base = config.normalizedUrl
        var connected = false
        var lastError: Throwable? = null
        for (path in candidateOrder(base)) {
            val outcome = runCatching {
                connector.open(config, path) {
                    connected = true
                    winners[base] = path
                    onConnected()
                }.collect { emit(it) }
            }
            if (outcome.isSuccess) return@flow // server closed a healthy stream
            val error = outcome.exceptionOrNull()
                ?: IllegalStateException("event stream could not be opened")
            if (error is CancellationException) throw error
            lastError = error
            // Once connected, failures are CONNECTION drops (supervisor will
            // back off and retry the memoized winner) — not probe misses.
            if (connected || !isProbeMiss(error)) throw error
        }
        throw lastError ?: IllegalStateException("no event-stream endpoint could be opened")
    }

    /** Memoized winner first, then the declared candidates. */
    internal fun candidateOrder(base: String): List<String> {
        val winner = winners[base] ?: return EVENT_CANDIDATES
        return listOf(winner) + EVENT_CANDIDATES.filter { it != winner }
    }

    /** @return the currently memoized winner for [base], if any (test seam). */
    internal fun winnerFor(base: String): String? = winners[base]

    private fun isProbeMiss(error: Throwable?): Boolean =
        error is UnsupportedResponseException ||
            error is SerializationException ||
            (error is OpenCodeHttpException && (error.code == 404 || error.code == 400))

    internal companion object {
        val EVENT_CANDIDATES = listOf("/event", "/global/event")
    }
}

/** Content type straight from the header (Ktor exposes no HttpResponse ext here). */
private fun HttpResponse.responseContentType(): ContentType? =
    headers[HttpHeaders.ContentType]?.let { runCatching { ContentType.parse(it) }.getOrNull() }

/** Production connector on top of Ktor's SSE client plugin. */
private class KtorSseConnector(
    private val client: HttpClient,
) : SseConnector {

    override suspend fun open(
        config: ServerConfig,
        path: String,
        onConnected: () -> Unit,
    ): Flow<String> = flow {
        val session = try {
            client.sseSession {
                url(config.normalizedUrl + path)
                header(HttpHeaders.Accept, "text/event-stream")
                if (config.token.isNotBlank()) {
                    header(HttpHeaders.Authorization, "Bearer ${config.token}")
                }
                timeout(infiniteTimeouts)
            }
        } catch (sse: SSEClientException) {
            // A carried response means the server ANSWERED the wrong way
            // (404, SPA html on 200, non-event content type): probe miss.
            // A response-less failure is transport-level: propagate.
            val status = sse.response?.status?.value
            if (status != null) {
                throw UnsupportedResponseException(
                    path,
                    "HTTP $status ${sse.response?.responseContentType() ?: "-"}",
                )
            }
            throw sse
        }
        try {
            val response = session.call.response
            val contentType = response.responseContentType()?.withoutParameters()
            if (response.status.value !in 200..299 ||
                (contentType != null && contentType != ContentType.Text.EventStream)
            ) {
                throw UnsupportedResponseException(
                    path,
                    "HTTP ${response.status.value} ${contentType ?: "-"}",
                )
            }
            onConnected()
            session.incoming.collect { event ->
                val data = event.data?.trim()
                if (!data.isNullOrEmpty()) emit(data)
            }
        } finally {
            session.cancel()
        }
    }
}

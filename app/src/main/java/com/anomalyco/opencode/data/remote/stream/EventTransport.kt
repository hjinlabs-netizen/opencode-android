package com.anomalyco.opencode.data.remote.stream

import com.anomalyco.opencode.domain.model.ServerConfig
import kotlinx.coroutines.flow.Flow

/**
 * Transport abstraction for the OpenCode server event feed.
 *
 * Implementations only need to turn a live connection into a cold [Flow] of
 * raw JSON frame payloads. Resilience (reconnect/backoff) and decoding live
 * in [OpenCodeStreamClient], so adding a WebSocket transport later is just
 * another implementation of this interface.
 */
interface EventTransport {

    /**
     * Open the event stream for [config] and emit raw JSON frames.
     *
     * The returned flow is **cold**: collecting it connects, and cancelling
     * the collection must release all resources. It completes normally when
     * the server closes the stream and throws (IOException et al.) on any
     * transport failure.
     *
     * [onConnected] is invoked once as soon as the transport-level handshake
     * succeeds (HTTP 200 / SSE stream accepted) — BEFORE any payload frame
     * arrives — so the supervisor can report [com.anomalyco.opencode.domain.model.StreamStatus.Connected]
     * immediately instead of waiting for the first data event (servers may
     * keep a healthy stream quiet with comment-only keep-alives).
     */
    fun frames(config: ServerConfig, onConnected: () -> Unit = {}): Flow<String>
}

package com.anomalyco.opencode.data.remote

import io.ktor.client.plugins.HttpTimeoutConfig

/**
 * Shared per-request timeout override for the server's two long-lived
 * interactions: the SSE event stream (`/event`) and the end-of-turn
 * `POST /session/{id}/message` long poll. Both routinely exceed the client
 * defaults, so request and socket inactivity timeouts are lifted while the
 * connect timeout keeps its sane client-level value.
 *
 * NOTE: the sentinel is `HttpTimeoutConfig.INFINITE_TIMEOUT_MS`
 * (Long.MAX_VALUE). Assigning `0` throws IllegalArgumentException from
 * HttpTimeoutConfig ("Only positive timeout values are allowed") — that
 * exact mistake once prevented the SSE stream from ever connecting.
 */
internal val infiniteTimeouts: HttpTimeoutConfig.() -> Unit = {
    requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
    socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
}

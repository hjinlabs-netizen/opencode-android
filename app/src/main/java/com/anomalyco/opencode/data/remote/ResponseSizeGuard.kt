package com.anomalyco.opencode.data.remote

import com.anomalyco.opencode.data.PayloadLimits
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.Json

/**
 * Raised by the bounded body reads when a response exceeds its budget. Mapped
 * by `ErrorMapping` to `OpenCodeError.ResponseTooLarge(maxBytes)` so it flows
 * through the existing `apiCall` -> `Result` -> typed-error pipeline — no
 * parallel error mechanism.
 */
class ResponseTooLargeException(val maxBytes: Long) :
    Exception("response body exceeds the $maxBytes-byte budget")

/**
 * Sprint M.1: HTTP-boundary response-size protection (the 76 MB
 * large-object-space spike in the device log came from full `bodyAsText()` +
 * `JsonElement` buffering BEFORE the decode caps applied).
 *
 * NOTE on mechanism: the Ktor `transformResponseBody` plugin hook only fires
 * for typed `body<T>()` receives (its signature carries a `TypeInfo`) and
 * never for `bodyAsText()`/raw-channel reads — which is exactly how the
 * largest payloads in this app (`getJson`: file content, diffs, listings)
 * are read. A hook-based guard would silently leave the main risk path
 * uncovered. Instead, every body read in `OpenCodeApi` — the single API
 * choke point all responses already flow through — goes through
 * [boundedBodyText]/[boundedBody] below. Engine-independent, deterministic,
 * directly testable.
 *
 * Protection per response, in order:
 *  1. Content-Length fast rejection when the header is present (chunked/gzip
 *     responses omit it and fall through to the counter);
 *  2. blocking bounded read of AT MOST `budget + 1` bytes — crossing the
 *     budget throws before any JSON parsing sees a byte of it;
 *  3. the remainder of the stream is cancelled, never buffered.
 *
 * SSE streams are exempt BY STRUCTURE: the event feed is consumed by
 * `KtorSseConnector` through the SSE session channel and never touches these
 * helpers (pinned by the real-engine integration suites plus the explicit
 * over-budget-stream test in `ResponseSizeGuardTest`).
 *
 * Budgets from the approved table, selected by request path:
 *  - `…/message` endpoints (history + end-of-turn long poll): 16 MB
 *  - health / provider / config metadata: 1 MB
 *  - everything else: 4 MB
 *
 * Truncation is deliberately NOT attempted: partial JSON is unparseable, so
 * an over-budget response is a hard, typed, localized failure instead.
 */
internal fun budgetFor(path: String): Long = when {
    path.endsWith("/message") -> PayloadLimits.MAX_RESPONSE_MESSAGES_BYTES
    path == "/global/health" || path == "/provider" || path == "/config" ->
        PayloadLimits.MAX_RESPONSE_SMALL_BYTES
    else -> PayloadLimits.MAX_RESPONSE_DEFAULT_BYTES
}

/**
 * Reads the response body as text under the [maxBytes] budget (default: the
 * path-derived one). Throws [ResponseTooLargeException] on overflow — fast
 * via the declared `Content-Length` when present, hard via the byte counter
 * otherwise.
 */
internal suspend fun HttpResponse.boundedBodyText(
    maxBytes: Long = budgetFor(call.request.url.encodedPath),
): String {
    val declared = contentLength()
    if (declared != null && declared > maxBytes) {
        throw ResponseTooLargeException(maxBytes)
    }
    val channel = bodyAsChannel()
    val bytes = channel.readUpTo(maxBytes.toInt() + 1)
    channel.cancel(null) // drop any remainder without buffering it
    if (bytes.size > maxBytes) {
        throw ResponseTooLargeException(maxBytes)
    }
    return String(bytes, Charsets.UTF_8)
}

/** Typed equivalent of [boundedBodyText] for `body<T>()` call sites. */
internal suspend inline fun <reified T> HttpResponse.boundedBody(
    json: Json,
    maxBytes: Long = budgetFor(call.request.url.encodedPath),
): T = json.decodeFromString(boundedBodyText(maxBytes))

/**
 * Reads AT MOST [limit] bytes from the channel, suspending until EOF or the
 * limit (Ktor 3's `readAvailable` blocks until at least one byte). Stops at
 * the limit without consuming the rest; never throws on overflow — the
 * caller decides what exceeding means.
 */
internal suspend fun ByteReadChannel.readUpTo(limit: Int): ByteArray {
    if (limit <= 0) return ByteArray(0)
    val out = ByteArrayOutputStream()
    val buf = ByteArray(minOf(8 * 1024, limit))
    var remaining = limit
    while (remaining > 0) {
        val n = readAvailable(buf, 0, minOf(buf.size, remaining))
        when {
            n < 0 -> break // EOF
            n > 0 -> {
                out.write(buf, 0, n)
                remaining -= n
            }
            else -> kotlinx.coroutines.yield() // defensive: nothing readable
        }
    }
    return out.toByteArray()
}

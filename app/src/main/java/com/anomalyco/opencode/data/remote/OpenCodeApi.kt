package com.anomalyco.opencode.data.remote

import com.anomalyco.opencode.data.remote.dto.CreateSessionRequest
import com.anomalyco.opencode.data.remote.dto.MessageDto
import com.anomalyco.opencode.data.remote.dto.SendMessageRequest
import com.anomalyco.opencode.data.remote.dto.SessionDto
import com.anomalyco.opencode.domain.model.HealthInfo
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin typed wrapper over the OpenCode HTTP API (v1 route group).
 *
 * One method per endpoint, keeping serialization concerns out of the
 * repository layer. All requests are built against the caller-supplied
 * [baseUrl]/[token] (resolved once by the repositories from the active
 * server config), so this class stays stateless and trivially testable.
 *
 * JSON encoding/decoding is delegated to the `ContentNegotiation` feature
 * configured with the shared lenient [kotlinx.serialization.json.Json].
 */
@Singleton
class OpenCodeApi @Inject constructor(
    private val client: HttpClient,
) {

    /**
     * Probe the remote server with `GET /global/health`.
     *
     * The endpoint returns `200` with a JSON body (version metadata on some
     * builds, an empty object on others). Any non-2xx or transport failure
     * throws and is mapped by the repository to a descriptive error.
     */
    suspend fun health(baseUrl: String, token: String): HealthInfo {
        val response = authorizedGet(baseUrl, token, HEALTH_PATH)
        // Body may be empty; fall back gracefully instead of crashing the parser.
        val text = response.bodyAsText().trim()
        if (text.isEmpty() || text == "{}") return HealthInfo.empty()
        return runCatching { response.body<HealthInfo>() }.getOrElse { HealthInfo.empty() }
    }

    /** `GET /session` — list all sessions known to the server. */
    suspend fun listSessions(baseUrl: String, token: String): List<SessionDto> =
        authorizedGet(baseUrl, token, SESSIONS_PATH).body()

    /** `POST /session` — create a new session and return its descriptor. */
    suspend fun createSession(
        baseUrl: String,
        token: String,
        request: CreateSessionRequest,
    ): SessionDto = authorizedPost(baseUrl, token, SESSIONS_PATH, request).body()

    /** `GET /session/{id}` — fetch a single session's details. */
    suspend fun getSession(
        baseUrl: String,
        token: String,
        sessionId: String,
    ): SessionDto = authorizedGet(baseUrl, token, "$SESSIONS_PATH/$sessionId").body()

    /** `GET /session/{id}/message` — fetch the message history (info + parts). */
    suspend fun listMessages(
        baseUrl: String,
        token: String,
        sessionId: String,
    ): List<MessageDto> = authorizedGet(
        baseUrl,
        token,
        "$SESSIONS_PATH/$sessionId/$MESSAGES_SUFFIX",
    ).body()

    /**
     * `POST /session/{id}/message` — send a prompt and return the created user
     * message envelope. Assistant output for this prompt arrives separately
     * over the event stream.
     */
    suspend fun sendPrompt(
        baseUrl: String,
        token: String,
        sessionId: String,
        request: SendMessageRequest,
    ): MessageDto = authorizedPost(
        baseUrl,
        token,
        "$SESSIONS_PATH/$sessionId/$MESSAGES_SUFFIX",
        request,
    ).body()

    // ---- shared request/auth helpers ----

    private suspend fun authorizedGet(
        baseUrl: String,
        token: String,
        path: String,
    ): HttpResponse = validated { client.get("$baseUrl$path") { authorize(token) } }

    private suspend fun authorizedPost(
        baseUrl: String,
        token: String,
        path: String,
        body: Any,
    ): HttpResponse = validated {
        client.post("$baseUrl$path") {
            authorize(token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }

    private suspend fun validated(block: suspend () -> HttpResponse): HttpResponse {
        val response = block()
        if (!response.status.isSuccess()) {
            throw OpenCodeHttpException(response.status.value, response.bodyAsText())
        }
        return response
    }

    private fun HttpRequestBuilder.authorize(token: String) {
        if (token.isNotBlank()) {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
    }

    private companion object {
        const val HEALTH_PATH = "/global/health"
        const val SESSIONS_PATH = "/session"
        const val MESSAGES_SUFFIX = "message"
    }
}

/** Raised for non-2xx API responses; [code] and body text preserved for UI. */
class OpenCodeHttpException(
    val code: Int,
    val bodyText: String,
) : Exception("OpenCode server returned HTTP $code")

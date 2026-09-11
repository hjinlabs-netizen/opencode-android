package com.anomalyco.opencode.data.remote

import com.anomalyco.opencode.data.remote.dto.ConfigDto
import com.anomalyco.opencode.data.remote.dto.ConfigPatchDto
import com.anomalyco.opencode.data.remote.dto.CreateSessionRequest
import com.anomalyco.opencode.data.remote.dto.FileContentDto
import com.anomalyco.opencode.data.remote.dto.FileDiffDto
import com.anomalyco.opencode.data.remote.dto.FileNodeDto
import com.anomalyco.opencode.data.remote.dto.MessageDto
import com.anomalyco.opencode.data.remote.dto.MessageInfoDto
import com.anomalyco.opencode.data.remote.dto.PermissionResponseRequest
import com.anomalyco.opencode.data.remote.dto.ProviderListDto
import com.anomalyco.opencode.data.remote.dto.QuestionReplyRequest
import com.anomalyco.opencode.data.remote.dto.SendMessageRequest
import com.anomalyco.opencode.data.remote.dto.SessionDto
import com.anomalyco.opencode.domain.model.HealthInfo
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
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
     *
     * This endpoint is a LONG POLL: it resolves only after the entire turn
     * (every tool invocation and token batch) finishes, routinely exceeding
     * the client's 20s default — so request and socket timeouts are lifted
     * for this call. The connect timeout stays, and a dropped request is
     * harmless: the turn's events arrive over SSE regardless.
     *
     * The request itself is the side-effect that matters: servers differ on
     * the response shape (envelope, bare info, or nothing useful), so a
     * decode failure degrades to an empty envelope rather than reporting a
     * send failure (the UI would erase an accepted prompt).
     */
    suspend fun sendPrompt(
        baseUrl: String,
        token: String,
        sessionId: String,
        request: SendMessageRequest,
    ): MessageDto {
        val response = authorizedPost(
            baseUrl,
            token,
            "$SESSIONS_PATH/$sessionId/$MESSAGES_SUFFIX",
            request,
        ) { timeout(infiniteTimeouts) }
        return runCatching { response.body<MessageDto>() }
            .recoverCatching { MessageDto(info = response.body<MessageInfoDto>()) }
            .getOrElse { MessageDto() }
    }

    /**
     * `POST /permission/{requestId}` — resolve a pending permission request
     * with the server vocabulary ("once" | "always" | "reject").
     */
    suspend fun respondPermission(
        baseUrl: String,
        token: String,
        requestId: String,
        request: PermissionResponseRequest,
    ): Unit = authorizedPost(
        baseUrl,
        token,
        "$PERMISSIONS_PATH/$requestId",
        request,
    ).let { }

    /**
     * `POST /question/{questionId}/reply` — answer a pending question.
     * [QuestionReplyRequest.answers] is one list of labels per question.
     */
    suspend fun respondQuestion(
        baseUrl: String,
        token: String,
        questionId: String,
        request: QuestionReplyRequest,
    ): Unit = authorizedPost(
        baseUrl,
        token,
        "$QUESTIONS_PATH/$questionId/reply",
        request,
    ).let { }

    // ---- filesystem & model catalog (Phase 3) ----

    /** `GET /fs/list?path=` — one level of the project directory listing. */
    suspend fun listFiles(baseUrl: String, token: String, path: String): List<FileNodeDto> =
        authorizedGet(baseUrl, token, FS_LIST_PATH) { parameter("path", path) }.body()

    /** `GET /fs/read?path=` — UTF-8 contents of a single file. */
    suspend fun readFile(baseUrl: String, token: String, path: String): FileContentDto =
        authorizedGet(baseUrl, token, FS_READ_PATH) { parameter("path", path) }.body()

    /** `GET /fs/diff[?path=]` — working-tree diff rows (raw patch text). */
    suspend fun diffFiles(baseUrl: String, token: String, path: String?): List<FileDiffDto> =
        authorizedGet(baseUrl, token, FS_DIFF_PATH) {
            if (!path.isNullOrEmpty()) parameter("path", path)
        }.body()

    /** `GET /provider` — catalog of supported providers/models. */
    suspend fun getProviders(baseUrl: String, token: String): ProviderListDto =
        authorizedGet(baseUrl, token, PROVIDER_PATH).body()

    /** `GET /config` — server configuration subset (active model). */
    suspend fun getConfig(baseUrl: String, token: String): ConfigDto =
        authorizedGet(baseUrl, token, CONFIG_PATH).body()

    /** `POST /config` — patch the configuration (model switching). */
    suspend fun updateConfig(
        baseUrl: String,
        token: String,
        patch: ConfigPatchDto,
    ): Unit = authorizedPost(baseUrl, token, CONFIG_PATH, patch).let { }

    // ---- shared request/auth helpers ----

    private suspend fun authorizedGet(
        baseUrl: String,
        token: String,
        path: String,
        configure: HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse = validated {
        client.get("$baseUrl$path") {
            authorize(token)
            configure()
        }
    }

    private suspend fun authorizedPost(
        baseUrl: String,
        token: String,
        path: String,
        body: Any,
        configure: HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse = validated {
        client.post("$baseUrl$path") {
            authorize(token)
            contentType(ContentType.Application.Json)
            setBody(body)
            configure()
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
        const val PERMISSIONS_PATH = "/permission"
        const val QUESTIONS_PATH = "/question"
        const val FS_LIST_PATH = "/fs/list"
        const val FS_READ_PATH = "/fs/read"
        const val FS_DIFF_PATH = "/fs/diff"
        const val PROVIDER_PATH = "/provider"
        const val CONFIG_PATH = "/config"
    }
}

/** Raised for non-2xx API responses; [code] and body text preserved for UI. */
class OpenCodeHttpException(
    val code: Int,
    val bodyText: String,
) : Exception("OpenCode server returned HTTP $code")

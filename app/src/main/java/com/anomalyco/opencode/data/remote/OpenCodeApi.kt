package com.anomalyco.opencode.data.remote

import com.anomalyco.opencode.domain.model.HealthInfo
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin typed wrapper over the OpenCode HTTP API (v1, `/global/*` & `/api/*` groups).
 *
 * Phase 1 only needs the health probe. As more features land (sessions,
 * messages, events) their calls are added here — one method per endpoint,
 * keeping serialization concerns out of the repository layer.
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
        val response: HttpResponse = client.get("$baseUrl${HEALTH_PATH}") {
            if (token.isNotBlank()) {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
        }
        if (!response.status.isSuccess()) {
            throw OpenCodeHttpException(response.status.value, response.bodyAsText())
        }
        // Body may be empty; fall back gracefully instead of crashing the parser.
        val text = response.bodyAsText().trim()
        if (text.isEmpty() || text == "{}") return HealthInfo.empty()
        return runCatching { response.body<HealthInfo>() }.getOrElse { HealthInfo.empty() }
    }

    companion object {
        private const val HEALTH_PATH = "/global/health"
    }
}

/** Raised for non-2xx API responses; [code] and body text preserved for UI. */
class OpenCodeHttpException(
    val code: Int,
    val bodyText: String,
) : Exception("OpenCode server returned HTTP $code")

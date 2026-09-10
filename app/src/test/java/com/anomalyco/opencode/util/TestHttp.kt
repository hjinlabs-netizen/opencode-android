package com.anomalyco.opencode.util

import com.anomalyco.opencode.domain.model.HealthInfo
import com.anomalyco.opencode.domain.model.ServerConfig
import com.anomalyco.opencode.domain.repository.ConnectionRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** [ConnectionRepository] double serving a fixed (absent) server config. */
class FakeConnectionRepository(
    server: ServerConfig? = ServerConfig("http://srv:4096/", "tok"),
) : ConnectionRepository {
    override val config: Flow<ServerConfig?> = MutableStateFlow(server)
    override suspend fun saveConfig(config: ServerConfig) = Unit
    override suspend fun clearConfig() = Unit
    override suspend fun checkHealth(config: ServerConfig): Result<HealthInfo> =
        Result.success(HealthInfo())
}

/** Response contract a scripted MockEngine handler returns. */
data class MockResponse(
    val status: HttpStatusCode = HttpStatusCode.OK,
    val body: String = "",
)

/**
 * JSON [HttpClient] backed by a scriptable, request-recording MockEngine.
 * Uses the PRODUCTION Json config so wire-shape regressions (e.g. null vs
 * omitted optional fields) reproduce identically in tests.
 */
fun recordingClient(
    captured: MutableList<HttpRequestData>,
    script: (HttpRequestData) -> MockResponse,
): HttpClient = HttpClient(
    MockEngine { request ->
        captured += request
        val answer = script(request)
        respond(
            content = answer.body,
            status = answer.status,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
    },
) {
    expectSuccess = false
    install(ContentNegotiation) {
        json(com.anomalyco.opencode.di.NetworkModule.opencodeJson())
    }
}

/** Extracts the JSON text a request posted via ContentNegotiation. */
fun HttpRequestData.postedJson(): String = (body as? TextContent)?.text ?: ""

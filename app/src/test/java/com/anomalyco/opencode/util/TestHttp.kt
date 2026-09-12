package com.anomalyco.opencode.util

import com.anomalyco.opencode.di.NetworkModule
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
import kotlinx.serialization.json.Json

/** Response contract a scripted MockEngine handler returns. */
data class MockResponse(
    val status: HttpStatusCode = HttpStatusCode.OK,
    val body: String = "",
    val contentType: String = "application/json",
)

/** The exact production Json config, for repository-side element decoding. */
fun testJson(): Json = NetworkModule.opencodeJson()


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
            headers = headersOf(HttpHeaders.ContentType, answer.contentType),
        )
    },
) {
    expectSuccess = false
    install(ContentNegotiation) {
        json(testJson())
    }
}

/** Extracts the JSON text a request posted via ContentNegotiation. */
fun HttpRequestData.postedJson(): String = (body as? TextContent)?.text ?: ""

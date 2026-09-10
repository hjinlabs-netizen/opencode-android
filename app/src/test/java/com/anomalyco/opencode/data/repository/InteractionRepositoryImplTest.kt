package com.anomalyco.opencode.data.repository

import com.anomalyco.opencode.domain.model.HealthInfo
import com.anomalyco.opencode.domain.model.PermissionDecision
import com.anomalyco.opencode.domain.model.ServerConfig
import com.anomalyco.opencode.domain.repository.ConnectionRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the exact HTTP calls the interaction repository makes: method,
 * path, bearer header and JSON body — plus the no-server-configured failure.
 */
class InteractionRepositoryImplTest {

    private val captured = mutableListOf<HttpRequestData>()

    private fun engineReturning(status: HttpStatusCode) = MockEngine { request ->
        captured += request
        respond(content = "", status = status, headers = headersOf(HttpHeaders.ContentType, "application/json"))
    }

    private fun repository(
        status: HttpStatusCode = HttpStatusCode.OK,
        config: ServerConfig? = ServerConfig("http://srv:4096/", "tok"),
    ): InteractionRepositoryImpl {
        val client = HttpClient(engineReturning(status)) {
            expectSuccess = false
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
            }
        }
        val api = com.anomalyco.opencode.data.remote.OpenCodeApi(client)
        return InteractionRepositoryImpl(api, FakeConnection(config))
    }

    private class FakeConnection(config: ServerConfig?) : ConnectionRepository {
        override val config: Flow<ServerConfig?> = MutableStateFlow(config)
        override suspend fun saveConfig(config: ServerConfig) = Unit
        override suspend fun clearConfig() = Unit
        override suspend fun checkHealth(config: ServerConfig): Result<HealthInfo> =
            Result.success(HealthInfo())
    }

    @Test
    fun `allow posts the once response to the permission path with bearer`() = runTest {
        val repo = repository()
        val result = repo.respondPermission("per-1", PermissionDecision.ALLOW)

        assertTrue(result.isSuccess)
        val request = captured.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("/permission/per-1", request.url.encodedPath)
        assertEquals("Bearer tok", request.headers[HttpHeaders.Authorization])
        val body = (request.body as TextContent).text
        assertEquals("""{"response":"once"}""", body)
    }

    @Test
    fun `deny maps to the reject wire value`() = runTest {
        repository().respondPermission("p2", PermissionDecision.DENY)
        val body = (captured.single().body as TextContent).text
        assertTrue(body.contains("\"reject\""))
    }

    @Test
    fun `answer posts an answers matrix to the question reply path`() = runTest {
        val repo = repository()
        val result = repo.respondQuestion("q-9", listOf("Compose", "dark"))

        assertTrue(result.isSuccess)
        val request = captured.single()
        assertEquals("/question/q-9/reply", request.url.encodedPath)
        val body = (request.body as TextContent).text
        // answers is a matrix: one list per question.
        assertEquals("""{"answers":[["Compose","dark"]]}""", body)
    }

    @Test
    fun `server error becomes a friendly failure`() = runTest {
        val repo = repository(status = HttpStatusCode.InternalServerError)
        val result = repo.respondPermission("p3", PermissionDecision.ALLOW)

        assertTrue(result.isFailure)
        assertEquals(
            "Sunucu hatası (HTTP 500) — OpenCode servisini kontrol et.",
            result.exceptionOrNull()?.message,
        )
    }

    @Test
    fun `missing server config fails without an HTTP call`() = runTest {
        val repo = repository(config = null)
        val result = repo.respondQuestion("q1", listOf("x"))

        assertTrue(result.isFailure)
        assertTrue(captured.isEmpty())
    }
}

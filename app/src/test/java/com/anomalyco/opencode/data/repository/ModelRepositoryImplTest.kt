package com.anomalyco.opencode.data.repository

import com.anomalyco.opencode.data.remote.OpenCodeApi
import com.anomalyco.opencode.util.FakeConnectionRepository
import com.anomalyco.opencode.util.MockResponse
import com.anomalyco.opencode.util.postedJson
import com.anomalyco.opencode.util.recordingClient
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `/provider` + `/config` decoding: current-model flagging, graceful
 * degradation when the config read fails, and the exact switch payload.
 */
class ModelRepositoryImplTest {

    private val captured = mutableListOf<HttpRequestData>()

    private val providerJson = """
        {
          "all": [
            {"id":"anthropic","name":"Anthropic","models":[
                {"id":"claude-x","name":"Claude X","limit":{"context":200000}},
                {"id":"claude-y","title":"Claude Y"}
            ]},
            {"id":"openai","name":"OpenAI","models":[
                {"id":"gpt-z","name":"GPT Z","cost":{"input":2.5}}
            ]}
          ],
          "default": {"anthropic": "claude-x"},
          "connected": ["anthropic"]
        }
    """.trimIndent()

    private fun repository(
        configBody: MockResponse = MockResponse(body = """{"model":"anthropic/claude-y"}"""),
    ) = ModelRepositoryImpl(
            OpenCodeApi(
                recordingClient(captured) { request ->
                    when (request.url.encodedPath) {
                        "/config" -> configBody
                        else -> MockResponse(body = providerJson)
                    }
                },
            ),
            FakeConnectionRepository(),
        )

    @Test
    fun `fetchProviders reads config first then catalog and flags the current model`() = runTest {
        val providers = repository().fetchProviders().getOrThrow()

        assertEquals(listOf("/config", "/provider"), captured.map { it.url.encodedPath })
        assertEquals(2, providers.size)
        val anthropic = providers.first { it.providerId == "anthropic" }
        assertEquals("Anthropic", anthropic.displayName)
        assertTrue(anthropic.isConnected)
        assertEquals("Claude Y", anthropic.currentModel?.displayName)
        assertEquals(200000, anthropic.models.first().contextLength)
        // title fallback for model without name.
        assertEquals("Claude Y", anthropic.models[1].displayName)
        // Only one model is current across the whole catalog.
        assertEquals(1, providers.flatMap { it.models }.count { it.isCurrent })
        // Providers absent from `connected` degrade to disconnected.
        assertFalse(providers.first { it.providerId == "openai" }.isConnected)
    }

    @Test
    fun `broken config still yields an unflagged catalog`() = runTest {
        val providers = repository(configBody = MockResponse(status = HttpStatusCode.InternalServerError))
            .fetchProviders()
            .getOrThrow()

        assertEquals(2, providers.size)
        assertTrue(providers.flatMap { it.models }.none { it.isCurrent })
    }

    @Test
    fun `setActiveModel posts the qualified model id to config`() = runTest {
        val result = repository().setActiveModel("openai", "gpt-z")

        assertTrue(result.isSuccess)
        val request = captured.single { it.method == HttpMethod.Post }
        assertEquals("/config", request.url.encodedPath)
        assertEquals("""{"model":"openai/gpt-z"}""", request.postedJson())
        assertEquals("Bearer tok", request.headers[io.ktor.http.HttpHeaders.Authorization])
    }

    @Test
    fun `config rejection maps to friendly error`() = runTest {
        val repo = ModelRepositoryImpl(
            OpenCodeApi(
                recordingClient(captured) { MockResponse(status = HttpStatusCode.Forbidden) },
            ),
            FakeConnectionRepository(),
        )
        val result = repo.setActiveModel("bad", "model")

        assertFalse(result.isSuccess)
        val failure = result.exceptionOrNull()!!
        assertTrue(failure.message!!.contains("Kimlik doğrulama başarısız"))
        assertTrue(failure.cause is com.anomalyco.opencode.data.remote.OpenCodeHttpException)
    }
}

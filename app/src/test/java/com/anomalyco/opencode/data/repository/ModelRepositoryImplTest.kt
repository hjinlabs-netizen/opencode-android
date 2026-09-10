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
        providerBody: String = providerJson,
    ) = ModelRepositoryImpl(
            OpenCodeApi(
                recordingClient(captured) { request ->
                    when (request.url.encodedPath) {
                        "/config" -> configBody
                        else -> MockResponse(body = providerBody)
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
        // OpenCode omits/mismatches the optional `connected` hint; providers
        // that advertise models stay selectable (models presence wins).
        assertTrue(providers.first { it.providerId == "openai" }.isConnected)
    }

    @Test
    fun `provider with no models and a foreign connected list stays disconnected`() = runTest {
        val body = """
            {"all":[{"id":"ghostly","name":"Ghostly","models":[]}],"connected":["someone-else"]}
        """.trimIndent()
        val providers = repository(providerBody = body).fetchProviders().getOrThrow()

        assertFalse(providers.single().isConnected)
    }

    @Test
    fun `connected hint matches provider id case-insensitively`() = runTest {
        val body = """
            {"all":[{"id":"Anthropic","models":[{"id":"claude-x"}]}],"connected":["anthropic"]}
        """.trimIndent()
        val providers = repository(providerBody = body).fetchProviders().getOrThrow()

        assertTrue(providers.single().isConnected)
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

    // ---- map-shaped `models` (the live `/provider` payload format) ----------

    @Test
    fun `models served as an object keyed by model id parse into the catalog`() = runTest {
        val body = """
            {"all":[{"id":"p","name":"P","models":{"model-id":{"id":"model-id","name":"Model"}}}],"connected":[]}
        """.trimIndent()
        val providers = repository(
            configBody = MockResponse(body = """{"model":"p/model-id"}"""),
            providerBody = body,
        ).fetchProviders().getOrThrow()

        val model = providers.single().models.single()
        assertEquals("model-id", model.modelId)
        assertEquals("Model", model.displayName)
        assertTrue(model.isCurrent)
        assertEquals("p/model-id", model.qualifiedId)
    }

    @Test
    fun `qualified map keys do not double-prefix the provider in ids or switches`() = runTest {
        val body = """
            {"all":[{"id":"subconscious","name":"Sub","models":{
                "subconscious/glm-5.2":{"id":"subconscious/glm-5.2","name":"GLM 5.2"}
            }}],"connected":["subconscious"]}
        """.trimIndent()
        val repo = repository(
            configBody = MockResponse(body = """{"model":"subconscious/glm-5.2"}"""),
            providerBody = body,
        )
        val providers = repo.fetchProviders().getOrThrow()

        val model = providers.single().models.single()
        assertEquals("glm-5.2", model.modelId)
        assertEquals("subconscious/glm-5.2", model.qualifiedId)
        assertTrue(model.isCurrent) // config value matches despite the raw key form

        repo.setActiveModel(model.providerId, model.modelId)
        val post = captured.single { it.method == HttpMethod.Post }
        assertEquals("""{"model":"subconscious/glm-5.2"}""", post.postedJson())
    }

    @Test
    fun `map entries without an id fall back to the object key`() = runTest {
        val body = """
            {"all":[{"id":"p","models":{"orphan-model":{}}}], "connected":[]}
        """.trimIndent()
        val providers = repository(providerBody = body).fetchProviders().getOrThrow()

        val model = providers.single().models.single()
        assertEquals("orphan-model", model.modelId)
        // name/title missing → key doubles as the display fallback.
        assertEquals("orphan-model", model.displayName)
        assertNull(model.contextLength)
    }

    @Test
    fun `individually broken model entries are skipped without losing the catalog`() = runTest {
        val body = """
            {"all":[{"id":"p","models":{
                "m1":"not-an-object",
                "m2":{"id":"m2","name":"Fine"}
            }}],"connected":[]}
        """.trimIndent()
        val providers = repository(providerBody = body).fetchProviders().getOrThrow()

        val models = providers.single().models
        assertEquals(1, models.size)
        assertEquals("m2", models.single().modelId)
    }
}

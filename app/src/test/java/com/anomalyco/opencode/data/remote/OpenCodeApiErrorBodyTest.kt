package com.anomalyco.opencode.data.remote

import com.anomalyco.opencode.data.PayloadLimits
import com.anomalyco.opencode.domain.error.OpenCodeError
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sprint M.2: HTTP error bodies are buffered BOUNDED (4 KB) instead of the
 * old unbounded `bodyAsText()`, while the status code and the useful prefix
 * survive for `OpenCodeHttpException` -> typed `Http(code, snippet)`.
 */
class OpenCodeApiErrorBodyTest {

    private fun api(status: HttpStatusCode, errorBody: String): OpenCodeApi =
        OpenCodeApi(
            HttpClient(MockEngine {
                respond(
                    content = errorBody,
                    status = status,
                    headers = headersOf(HttpHeaders.ContentType, "text/html"),
                )
            }),
            com.anomalyco.opencode.di.NetworkModule.provideJson(),
        )

    private fun failure(api: OpenCodeApi): OpenCodeHttpException =
        runBlocking {
            runCatching { api.getJson("http://localhost", "", "/session") }
        }.exceptionOrNull() as OpenCodeHttpException

    @Test
    fun `huge error bodies are truncated to the 4 KB bound`() {
        val huge = "e".repeat(200 * 1024)
        val thrown = failure(api(HttpStatusCode.InternalServerError, huge))
        assertEquals(500, thrown.code)
        assertTrue(
            "expected <= ${PayloadLimits.MAX_ERROR_BODY_BYTES}, got ${thrown.bodyText.length}",
            thrown.bodyText.length <= PayloadLimits.MAX_ERROR_BODY_BYTES,
        )
        assertTrue(thrown.bodyText.startsWith("eee"))
    }

    @Test
    fun `small error bodies are preserved verbatim for diagnostics`() {
        val thrown = failure(api(HttpStatusCode.BadRequest, """{"error":"not a git repository"}"""))
        assertEquals(400, thrown.code)
        assertEquals("""{"error":"not a git repository"}""", thrown.bodyText)
    }

    @Test
    fun `empty error bodies stay empty strings as before`() {
        val thrown = failure(api(HttpStatusCode.InternalServerError, ""))
        assertEquals("", thrown.bodyText)
        val mapped = thrown.toOpenCodeError()
        assertEquals(OpenCodeError.Http(500, null), mapped)
    }
}

package com.anomalyco.opencode.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Network wiring: one shared [HttpClient] for the whole app.
 *
 * - OkHttp engine (HTTP/2, solid Android support)
 * - Kotlinx JSON with lenient settings — the OpenCode API evolves quickly,
 *   unknown fields must never crash the client
 * - Conservative timeouts tuned for a LAN/remote coding server
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        coerceInputValues = true
    }

    @Provides
    @Singleton
    fun provideHttpClient(json: Json): HttpClient = HttpClient(OkHttp) {
        expectSuccess = false // We handle status codes ourselves in OpenCodeApi.
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            // The base client carries server-wide defaults; per-request
            // overrides are still possible via HttpRequestBuilder.timeout.
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
            socketTimeoutMillis = SOCKET_TIMEOUT_MS
        }
        install(Logging) {
            // Keep bodies out of logs: they can contain code and secrets.
            level = LogLevel.INFO
        }
        engine {
            config {
                connectTimeout(5, TimeUnit.SECONDS)
                readTimeout(30, TimeUnit.SECONDS)
                retryOnConnectionFailure(true)
            }
        }
    }

    private const val REQUEST_TIMEOUT_MS = 20_000L
    private const val CONNECT_TIMEOUT_MS = 10_000L
    private const val SOCKET_TIMEOUT_MS = 20_000L
}

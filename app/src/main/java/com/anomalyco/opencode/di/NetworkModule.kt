package com.anomalyco.opencode.di

import com.anomalyco.opencode.BuildConfig
import com.anomalyco.opencode.data.remote.AndroidDebugLogger
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
import io.ktor.client.plugins.sse.SSE
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import okhttp3.Dispatcher
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
    fun provideJson(): Json = opencodeJson()

    /**
     * Single source of truth for wire JSON behaviour — production and unit
     * tests encode/decode through this exact configuration.
     *
     * `explicitNulls = false` is load-bearing: with the kotlinx default the
     * client sent `POST /session {"title":null,"agent":null}`, which the
     * OpenCode server schema rejects with HTTP 400. Optional fields must be
     * ABSENT (an empty `{}` body), not null.
     */
    internal fun opencodeJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        coerceInputValues = true
        explicitNulls = false
    }

    @Provides
    @Singleton
    fun provideHttpClient(json: Json): HttpClient = HttpClient(OkHttp) {
        expectSuccess = false // We handle status codes ourselves in OpenCodeApi.
        install(ContentNegotiation) { json(json) }
        install(SSE) // Enables `client.sseSession { ... }` for the event stream.
        install(HttpTimeout) {
            // The base client carries server-wide defaults; per-request
            // overrides are still possible via HttpRequestBuilder.timeout.
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
            socketTimeoutMillis = SOCKET_TIMEOUT_MS
        }
        // Sprint L.1: HTTP logging exists ONLY in debug builds. INFO level
        // never emits headers or bodies, and AndroidDebugLogger additionally
        // redacts URL queries/userinfo and drops credential-bearing lines.
        // Release builds do not install the plugin at all (zero HTTP logging,
        // and the SLF4J no-provider warning is gone with it).
        if (BuildConfig.DEBUG) {
            install(Logging) {
                level = LogLevel.INFO
                logger = AndroidDebugLogger
            }
        }
        engine {
            // Ktor 3.1.3 race condition: OkHttpSSESession.onFailure can fire
            // before originResponse is initialized (JVM field visibility gap
            // between constructor and OkHttp callback thread). The NPE escapes
            // to OkHttp's thread pool, killing the process. Install a custom
            // UncaughtExceptionHandler on the dispatcher threads so the NPE is
            // logged rather than crashing the app — the SSE supervisor will
            // observe the connection drop and retry with backoff.
            val threadCounter = AtomicInteger()
            config {
                connectTimeout(5, TimeUnit.SECONDS)
                // Long-lived SSE connections rely on server keep-alives;
                // event-stream requests additionally opt out of the timeout
                // plugin via per-request `timeout { ... }` overrides.
                readTimeout(90, TimeUnit.SECONDS)
                retryOnConnectionFailure(true)
                dispatcher(
                    Dispatcher(
                        Executors.newCachedThreadPool(
                            object : ThreadFactory {
                                override fun newThread(r: Runnable): Thread {
                                    val thread = Thread(r, "okhttp-dispatcher-${threadCounter.incrementAndGet()}")
                                    thread.isDaemon = true
                                    thread.uncaughtExceptionHandler =
                                        Thread.UncaughtExceptionHandler { t, e ->
                                            if (e is NullPointerException &&
                                                e.stackTrace.any {
                                                    it.className.contains("OkHttpSSESession") &&
                                                        it.methodName == "onFailure"
                                                }
                                            ) {
                                                android.util.Log.w(
                                                    "OkHttpSSE",
                                                    "Suppressed known Ktor SSE race NPE on ${t.name}",
                                                    e,
                                                )
                                            } else {
                                                android.util.Log.e(
                                                    "OkHttpSSE",
                                                    "Uncaught exception on ${t.name}",
                                                    e,
                                                )
                                            }
                                        }
                                    return thread
                                }
                            },
                        ),
                    ),
                )
            }
        }
    }

    private const val REQUEST_TIMEOUT_MS = 20_000L
    private const val CONNECT_TIMEOUT_MS = 10_000L
    private const val SOCKET_TIMEOUT_MS = 20_000L
}

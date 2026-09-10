package com.anomalyco.opencode.data.remote.stream

import com.anomalyco.opencode.di.ApplicationScope
import com.anomalyco.opencode.domain.model.ServerConfig
import com.anomalyco.opencode.domain.model.StreamEvent
import com.anomalyco.opencode.domain.model.StreamStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Resilient, app-wide view of the OpenCode server event feed.
 *
 * Wraps an [EventTransport] (SSE today, WebSocket tomorrow) with:
 *  - a supervised connection loop that reconnects with capped exponential
 *    backoff + jitter and resets the counter once a stream proves healthy
 *    (first frame received);
 *  - frame decoding into [StreamEvent]s via [StreamEventDecoder];
 *  - a hot [SharedFlow] contract: late subscribers only see *new* events —
 *    message history is fetched from the REST API via SessionRepository.
 *
 * Ordering is preserved per connection (buffered with [SUSPEND] overflow, so
 * a slow collector throttles the reader instead of dropping deltas).
 */
@Singleton
class OpenCodeStreamClient @Inject constructor(
    private val transport: EventTransport,
    private val decoder: StreamEventDecoder,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val mutex = Mutex()

    private var connectionJob: Job? = null
    private var activeConfig: ServerConfig? = null

    private val _events = MutableSharedFlow<StreamEvent>(
        replay = 0,
        extraBufferCapacity = EVENT_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    val events: SharedFlow<StreamEvent> = _events

    private val _status = MutableStateFlow<StreamStatus>(StreamStatus.Disconnected)
    val status: StateFlow<StreamStatus> = _status.asStateFlow()

    /** Test seam: deterministic jitter in backoff tests. */
    internal var jitter: () -> Double = { Random.nextDouble() }

    /**
     * Connect (or reconfigure) the stream to [config].
     * Idempotent for the same server; changing the URL/token restarts the loop.
     */
    suspend fun start(config: ServerConfig) = mutex.withLock {
        val normalized = config.copy(baseUrl = config.normalizedUrl)
        if (connectionJob?.isActive == true && activeConfig == normalized) return@withLock
        stopLocked()
        activeConfig = normalized
        connectionJob = scope.launch { supervise(normalized) }
    }

    /** Tear the connection down and stop reconnecting. */
    suspend fun stop() = mutex.withLock { stopLocked() }

    /** Drop the current connection and reconnect from scratch (resets backoff). */
    suspend fun reconnect() = mutex.withLock {
        val config = activeConfig ?: return@withLock
        stopLocked()
        connectionJob = scope.launch { supervise(config) }
    }

    private fun stopLocked() {
        connectionJob?.cancel()
        connectionJob = null
        activeConfig = null
        _status.value = StreamStatus.Disconnected
    }

    /**
     * Keeps [transport] connected for [config] until the job is cancelled.
     * Every drop is followed by a [computeBackoffDelay] sleep; the attempt
     * counter resets as soon as the stream delivers its first frame.
     */
    private suspend fun supervise(config: ServerConfig) {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            _status.value = StreamStatus.Connecting
            var healthy = false
            try {
                transport.frames(config)
                    .onEach { raw ->
                        if (!healthy) {
                            healthy = true
                            attempt = 0 // a working stream resets the backoff ladder
                            _status.value = StreamStatus.Connected
                        }
                        decoder.decodeAll(raw).forEach { event -> _events.emit(event) }
                    }
                    .collect()
                // Normal completion == the server closed our stream.
                _status.value = StreamStatus.Error("Sunucu bağlantıyı kapattı")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (!currentCoroutineContext().isActive) throw cancelled()
                _status.value = StreamStatus.Error(failure.message ?: "Yayın bağlantısı koptu")
            }

            // Compute the sleep for the *current* ladder rung first so the
            // very first failure waits the 1s base, then advance the ladder
            // only for unhealthy drops. A healthy stream already reset it.
            val waitMillis = computeBackoffDelay(attempt, jitter())
            if (!healthy) attempt++
            delay(waitMillis)
        }
    }

    private fun cancelled(): CancellationException =
        CancellationException("stream supervisor stopped")

    companion object {
        /** Enough headroom for bursty token streams before backpressure kicks in. */
        const val EVENT_BUFFER_CAPACITY = 256

        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L
        private const val BACKOFF_STEPS = 5
        private const val MAX_JITTER_RATIO = 0.25

        /**
         * Pure backoff formula: `min(1s * 2^attempt, 30s)` plus at most 25 %
         * jitter ([jitter] in 0..1). Exposed for deterministic unit tests.
         */
        fun computeBackoffDelay(attempt: Int, jitter: Double): Long {
            val exponent = attempt.coerceIn(0, BACKOFF_STEPS)
            val base = minOf(INITIAL_BACKOFF_MS * (1L shl exponent), MAX_BACKOFF_MS)
            return base + (base * MAX_JITTER_RATIO * jitter.coerceIn(0.0, 1.0)).toLong()
        }
    }
}

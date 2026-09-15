package com.anomalyco.opencode.data.remote.stream

/**
 * Tier B (Android device) runner of the shared [SseEngineIntegrationSpec].
 * Inherits ALL TEN matrix cases verbatim — same fixture, same production
 * stack, same assertions — but executed against Android's real network
 * stack inside the app process: platform DNS/routing for the loopback
 * ServerSocket, the app's network-security-config for cleartext HTTP, and
 * OkHttp's Android engine behavior. First execution is the CI emulator lane
 * (API 34, 1c.3); no behavior is expected to diverge from Tier A — that
 * equivalence IS the assertion.
 *
 * The overrides below scale ONLY the timing budgets to the platform (see
 * the KDoc in the base class: measured first-touch ART verification storms
 * and 2-vCPU swiftshader loopback latency in the first CI runs); the case
 * bodies, fixtures and assertions are the shared spec's, untouched.
 */
class SseEngineAndroidIntegrationTest : SseEngineIntegrationSpec() {

    override val timeoutMs: Long = 35_000L

    override val awaitDeadlineMs: Long = 30_000L

    override val retryObservationMs: Long = 5_000L

    /** Cold-verification soak may legitimately run long on first touch. */
    override val warmupMs: Long = 150_000L
}

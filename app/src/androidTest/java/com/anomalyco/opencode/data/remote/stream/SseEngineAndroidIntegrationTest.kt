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
 */
class SseEngineAndroidIntegrationTest : SseEngineIntegrationSpec()

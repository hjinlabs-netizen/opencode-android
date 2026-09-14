package com.anomalyco.opencode.data

/**
 * The approved Sprint 1b memory & cache limits table. A single choke object
 * so every cap is discoverable, reviewable and testable in one place.
 *
 * String limits are expressed in UTF-16 code units (≈ chars; heap cost is
 * ~2 bytes each). Truncation NEVER appends user-visible prose — callers get
 * a [Capped] result and surface it through typed flags (Sprint 1a rule: the
 * data layer carries no user-facing text).
 */
object PayloadLimits {

    /** `ToolCallPart.args` raw JSON: huge tool inputs truncate at decode. */
    const val MAX_TOOL_ARGS_CHARS = 64 * 1024

    /** Shell/tool captured output (`ShellPart.output` and stream echoes). */
    const val MAX_OUTPUT_CHARS = 128 * 1024

    /** File preview text held by the explorer (`FileContent.content`). */
    const val MAX_PREVIEW_CHARS = 512 * 1024

    /** Live chat transcript held by `ChatViewModel` (newest kept). */
    const val MAX_TRANSCRIPT_MESSAGES = 500

    /** Session rows cached by `SessionRepositoryImpl` (newest kept). */
    const val MAX_CACHED_SESSIONS = 1000

    /** Per-endpoint probe memoization: tracked servers before LRU eviction. */
    const val MAX_TRACKED_SERVERS = 8

    // ---- HTTP response budgets (Sprint M.1, ResponseSizeGuard) --------------

    /** Budget for ordinary JSON endpoints (sessions, files, diffs, listings). */
    const val MAX_RESPONSE_DEFAULT_BYTES = 4L * 1024 * 1024

    /** Budget for the message-carrying endpoints (`/session/{id}/message`). */
    const val MAX_RESPONSE_MESSAGES_BYTES = 16L * 1024 * 1024

    /** Budget for the small metadata endpoints (health, provider, config). */
    const val MAX_RESPONSE_SMALL_BYTES = 1L * 1024 * 1024

    /** Hard ceiling on buffered HTTP error bodies (Sprint M.2). */
    const val MAX_ERROR_BODY_BYTES = 4 * 1024

    /** Outcome of [cap]: text never exceeds [maxChars], [truncated] marks a cut. */
    data class Capped(val text: String, val truncated: Boolean)

    /**
     * Truncates [text] to at most [maxChars] UTF-16 code units without ever
     * splitting a surrogate pair: if the boundary would cut one, the cut
     * backs off one unit. Returns the same instance when no cap is needed.
     */
    fun cap(text: String, maxChars: Int): Capped {
        if (text.length <= maxChars) return Capped(text, false)
        var cut = maxChars
        // A high surrogate as the LAST kept char, followed by its low
        // surrogate in the dropped remainder, would split the pair.
        if (text[cut - 1].isHighSurrogate() && cut < text.length && text[cut].isLowSurrogate()) {
            cut--
        }
        return Capped(text.substring(0, cut), true)
    }

    fun toolArgs(raw: String): Capped = cap(raw, MAX_TOOL_ARGS_CHARS)

    fun output(raw: String): Capped = cap(raw, MAX_OUTPUT_CHARS)

    fun preview(raw: String): Capped = cap(raw, MAX_PREVIEW_CHARS)
}

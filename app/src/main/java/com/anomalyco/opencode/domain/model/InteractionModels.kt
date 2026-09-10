package com.anomalyco.opencode.domain.model

/**
 * Interactive agent→user requests (Phase 3).
 *
 * The server pauses the agent loop while these are PENDING and resumes it
 * once the client POSTs a resolution to the matching `/permission` or
 * `/question` endpoint. Wire vocabulary lives in the DTO/decoder layer;
 * these models are what the UI renders.
 */

/** A sensitive operation the agent wants to execute (file write, bash, ...). */
data class PermissionRequest(
    val requestId: String,
    val sessionId: String = "",
    /** Kind of operation: "edit", "bash", "webfetch", ... free-form wire value. */
    val type: String = "",
    val description: String = "",
    /** Affected file path for edit-like permissions (best effort, single pattern). */
    val path: String? = null,
    /** Proposed shell command for bash-like permissions. */
    val command: String? = null,
    /** Unified diff preview when the server includes one. */
    val diff: String? = null,
    val status: PermissionStatus = PermissionStatus.PENDING,
)

enum class PermissionStatus { PENDING, ALLOWED, DENIED }

/** User's reply to a [PermissionRequest]; maps to the server's wire vocabulary. */
enum class PermissionDecision(val wireValue: String) {
    ALLOW("once"),
    ALLOW_ALWAYS("always"),
    DENY("reject"),
}

/** An open-ended question the agent needs answered to continue. */
data class QuestionRequest(
    val questionId: String,
    val sessionId: String = "",
    val text: String = "",
    /** Predefined option labels; empty means free-text only. */
    val options: List<String> = emptyList(),
    /** Whether the server accepts an answer beyond [options]. */
    val allowCustomAnswer: Boolean = true,
    /** When true several options may be combined into the answer list. */
    val multiple: Boolean = false,
    val status: QuestionStatus = QuestionStatus.PENDING,
)

enum class QuestionStatus { PENDING, ANSWERED }

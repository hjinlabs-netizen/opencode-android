package com.anomalyco.opencode.data.remote.dto

import com.anomalyco.opencode.domain.model.PermissionDecision
import kotlinx.serialization.Serializable

/**
 * Request bodies for resolving interactive server requests.
 *
 * OpenCode v1 vocabulary: permissions accept `"once" | "always" | "reject"`
 * (see [PermissionDecision.wireValue]); questions accept an `answers` matrix
 * (one list of chosen labels per question) on `/reply`, or nothing on
 * `/reject`. Path constants in `OpenCodeApi` can be retuned per server build.
 */
@Serializable
data class PermissionResponseRequest(
    val response: String,
)

@Serializable
data class QuestionReplyRequest(
    val answers: List<List<String>>,
) {
    companion object {
        /** Convenience for the common single-question case. */
        fun of(answers: List<String>) = QuestionReplyRequest(answers = listOf(answers))
    }
}

/** Wire value for a user's permission decision. */
fun PermissionDecision.toWire(): String = wireValue

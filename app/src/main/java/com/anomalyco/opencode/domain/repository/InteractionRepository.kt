package com.anomalyco.opencode.domain.repository

import com.anomalyco.opencode.domain.model.PermissionDecision

/**
 * Resolves the interactive requests (permissions / questions) that arrive on
 * the event stream. Server state is authoritative: a call only reports whether
 * the reply POST was accepted; the resulting `session.next` events keep the
 * UI in sync.
 */
interface InteractionRepository {

    /** Allow ("once"), allow-always, or deny ("reject") a pending permission. */
    suspend fun respondPermission(requestId: String, decision: PermissionDecision): Result<Unit>

    /**
     * Answer a pending question with one or more selected/free-text labels
     * (multiple-choice questions send every chosen label).
     */
    suspend fun respondQuestion(questionId: String, answers: List<String>): Result<Unit>
}

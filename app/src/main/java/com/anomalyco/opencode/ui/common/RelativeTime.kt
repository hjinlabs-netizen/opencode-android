package com.anomalyco.opencode.ui.common

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Compact Turkish "time ago" formatting for session rows and message
 * headers. Falls back to a short date once a value is older than a week.
 */
fun formatRelativeTime(epochMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
    if (epochMillis <= 0L) return "—"
    val diff = (nowMillis - epochMillis).coerceAtLeast(0L)
    val minutes = diff / 60_000L
    val hours = diff / 3_600_000L
    val days = diff / 86_400_000L
    return when {
        minutes < 1 -> "şimdi"
        minutes < 60 -> "$minutes dk önce"
        hours < 24 -> "$hours sa önce"
        days < 7 -> "$days gün önce"
        else -> DATE_FORMAT.format(Date(epochMillis))
    }
}

private val DATE_FORMAT = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())

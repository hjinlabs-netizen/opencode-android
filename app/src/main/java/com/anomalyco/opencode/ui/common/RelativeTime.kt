package com.anomalyco.opencode.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.anomalyco.opencode.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Compact "time ago" formatting for session rows and message headers (Sprint
 * B: resource-backed for localization). Falls back to a short date once a
 * value is older than a week.
 */
internal sealed interface RelativeTime {
    data object Now : RelativeTime
    data class Minutes(val count: Long) : RelativeTime
    data class Hours(val count: Long) : RelativeTime
    data class Days(val count: Long) : RelativeTime
    data class DateOnly(val formatted: String) : RelativeTime
}

/** Pure bucket computation, unit-testable without Android resources. */
internal fun computeRelativeTime(epochMillis: Long, nowMillis: Long = System.currentTimeMillis()): RelativeTime {
    if (epochMillis <= 0L) return RelativeTime.Now
    val diff = (nowMillis - epochMillis).coerceAtLeast(0L)
    val minutes = diff / 60_000L
    val hours = diff / 3_600_000L
    val days = diff / 86_400_000L
    return when {
        minutes < 1 -> RelativeTime.Now
        minutes < 60 -> RelativeTime.Minutes(minutes)
        hours < 24 -> RelativeTime.Hours(hours)
        days < 7 -> RelativeTime.Days(days)
        else -> RelativeTime.DateOnly(DATE_FORMAT.format(Date(epochMillis)))
    }
}

@Composable
fun relativeTimeText(epochMillis: Long): String =
    when (val time = computeRelativeTime(epochMillis)) {
        RelativeTime.Now -> stringResource(R.string.time_now)
        is RelativeTime.Minutes -> stringResource(R.string.time_minutes_ago, time.count)
        is RelativeTime.Hours -> stringResource(R.string.time_hours_ago, time.count)
        is RelativeTime.Days -> stringResource(R.string.time_days_ago, time.count)
        is RelativeTime.DateOnly -> time.formatted
    }

private val DATE_FORMAT = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())

package com.anomalyco.opencode.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkScheme = darkColorScheme(
    primary = Violet,
    onPrimary = LightSurface,
    primaryContainer = DarkSurfaceVariant,
    onPrimaryContainer = Cyan,
    secondary = Cyan,
    onSecondary = DarkBackground,
    tertiary = Purple,
    onTertiary = DarkBackground,
    background = DarkBackground,
    onBackground = LightBackground,
    surface = DarkSurface,
    onSurface = LightBackground,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = MutedText,
    outline = DarkOutline,
    error = Danger,
    onError = LightSurface,
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF5B4FD1),
    onPrimary = LightSurface,
    primaryContainer = LightSurfaceVariant,
    onPrimaryContainer = Color(0xFF0E7490),
    secondary = Color(0xFF0E7490),
    onSecondary = LightSurface,
    tertiary = Color(0xFF9333EA),
    onTertiary = LightSurface,
    background = LightBackground,
    onBackground = Color(0xFF161D36),
    surface = LightSurface,
    onSurface = Color(0xFF161D36),
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = Color(0xFF5A6288),
    outline = LightOutline,
    error = Danger,
    onError = LightSurface,
)

/**
 * App-wide theme. Dark mode follows the system setting by default —
 * the dark scheme is the "signature" look, the light scheme is kept for
 * light-mode users.
 */
@Composable
fun OpenCodeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = OpenCodeTypography,
        content = content,
    )
}

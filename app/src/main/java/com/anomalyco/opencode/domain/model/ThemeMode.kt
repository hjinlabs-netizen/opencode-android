package com.anomalyco.opencode.domain.model

/**
 * User-selectable theme preference (Phase 4). [SYSTEM] follows the device
 * setting; the dark scheme is the app's signature look.
 */
enum class ThemeMode(val wireValue: String) {
    SYSTEM("system"),
    DARK("dark"),
    LIGHT("light"),
    ;

    companion object {
        /** Unknown or missing persisted values fall back to [SYSTEM]. */
        fun fromWire(value: String?): ThemeMode =
            entries.firstOrNull { it.wireValue.equals(value, ignoreCase = true) } ?: SYSTEM
    }
}

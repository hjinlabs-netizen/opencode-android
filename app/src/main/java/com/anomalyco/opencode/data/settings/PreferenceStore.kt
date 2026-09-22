package com.anomalyco.opencode.data.settings

/**
 * Minimal key-value abstraction over the preferences layer so the settings
 * repositories stay unit-testable on the JVM (an in-memory fake implements
 * this in tests; production uses [DataStorePreferenceStore]).
 */
interface PreferenceStore {
    fun getString(key: String, default: String? = null): String?
    fun putString(key: String, value: String)
}

package com.anomalyco.opencode.data.settings

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Minimal key-value abstraction over the preferences layer so the settings
 * repository stays unit-testable on the JVM (an in-memory fake implements
 * this in tests; production uses [SharedPreferenceStore]).
 */
interface PreferenceStore {
    fun getString(key: String, default: String? = null): String?
    fun putString(key: String, value: String)
}

/** Plain (unencrypted) SharedPreferences for non-secret UI preferences. */
@Singleton
class SharedPreferenceStore @Inject constructor(
    @ApplicationContext context: Context,
) : PreferenceStore {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override fun getString(key: String, default: String?): String? =
        prefs.getString(key, default)

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    private companion object {
        const val FILE_NAME = "opencode_prefs"
    }
}

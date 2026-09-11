package com.anomalyco.opencode.util

import com.anomalyco.opencode.data.settings.PreferenceStore

/** JVM [PreferenceStore] double backed by a plain map. */
class InMemoryPreferenceStore : PreferenceStore {
    val map = mutableMapOf<String, String>()
    override fun getString(key: String, default: String?): String? = map[key] ?: default
    override fun putString(key: String, value: String) {
        map[key] = value
    }
}

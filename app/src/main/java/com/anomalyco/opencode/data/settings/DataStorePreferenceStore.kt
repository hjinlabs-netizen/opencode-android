package com.anomalyco.opencode.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * DataStore-backed (P2-6) implementation of the synchronous [PreferenceStore]
 * seam, replacing the plain [android.content.SharedPreferences] store.
 *
 * DataStore is coroutine-native, but every consumer ([com.anomalyco.opencode.data.repository.SettingsRepositoryImpl]
 * theme seed, [com.anomalyco.opencode.data.repository.WorkspaceRepositoryImpl]
 * recents seed, [com.anomalyco.opencode.data.repository.ModelRepositoryImpl]
 * `preferredSelection()`) reads synchronously at startup — which is precisely
 * why this migration was deferred. The gap is closed by mirroring the on-disk
 * [Preferences] into an in-memory snapshot at construction: reads stay O(1)
 * and non-blocking, and the [PreferenceStore] contract is byte-for-byte
 * unchanged so the repositories, `MainActivity` and their JVM tests do not
 * move. The one-time construction load matches the blocking-disk-read profile
 * the old `getSharedPreferences()` first call already had.
 *
 * Existing installs are migrated transparently on the launch after upgrade:
 * values are copied from the legacy `opencode_prefs` SharedPreferences file
 * into DataStore (keys unchanged) only while DataStore is still empty, so the
 * copy can never clobber fresher DataStore-resident state.
 */
@Singleton
class DataStorePreferenceStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>,
) : PreferenceStore {

    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val snapshot = ConcurrentHashMap<String, String>()

    init {
        runBlocking(Dispatchers.IO) {
            if (dataStore.data.first().asMap().isEmpty()) migrateFromLegacyPrefs()
            hydrate()
        }
    }

    override fun getString(key: String, default: String?): String? = snapshot[key] ?: default

    override fun putString(key: String, value: String) {
        // Publish to the snapshot first so the next synchronous read is
        // consistent within this process, then persist off-thread (the same
        // durability window the old SharedPreferences.apply() provided).
        snapshot[key] = value
        writeScope.launch { dataStore.edit { prefs -> prefs[stringPreferencesKey(key)] = value } }
    }

    private suspend fun hydrate() {
        for ((key, value) in dataStore.data.first().asMap()) {
            if (value is String) snapshot[key.name] = value
        }
    }

    private suspend fun migrateFromLegacyPrefs() {
        val legacy = context.getSharedPreferences(LEGACY_FILE, Context.MODE_PRIVATE)
        val entries = legacy.all.entries.mapNotNull { (key, value) ->
            (value as? String)?.let { key to it }
        }
        if (entries.isEmpty()) return
        dataStore.edit { prefs ->
            entries.forEach { (key, value) -> prefs[stringPreferencesKey(key)] = value }
        }
        legacy.edit().clear().commit()
    }

    private companion object {
        // Same base name as the DataStore file, different medium
        // (shared_prefs/opencode_prefs.xml vs datastore/opencode_prefs.preferences_pb),
        // so the two never collide; the legacy file is only ever read here.
        const val LEGACY_FILE = "opencode_prefs"
    }
}

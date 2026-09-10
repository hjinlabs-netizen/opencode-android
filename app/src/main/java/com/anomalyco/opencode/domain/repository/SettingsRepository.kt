package com.anomalyco.opencode.domain.repository

import com.anomalyco.opencode.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow

/**
 * Non-secret UI preferences (Phase 4). Unlike the server token, these are
 * safe to store unencrypted and are read synchronously at startup.
 */
interface SettingsRepository {

    /** Persisted theme mode; emits on every change. */
    val themeMode: Flow<ThemeMode>

    suspend fun setThemeMode(mode: ThemeMode)
}

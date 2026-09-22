package com.anomalyco.opencode.di

import com.anomalyco.opencode.data.settings.DataStorePreferenceStore
import com.anomalyco.opencode.data.settings.PreferenceStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the plain (unencrypted) preference store used by [com.anomalyco.opencode.domain.repository.SettingsRepository].
 * DataStore-backed (P2-6); separate from the encrypted server-token store on purpose.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SettingsModule {

    @Binds
    @Singleton
    abstract fun bindPreferenceStore(impl: DataStorePreferenceStore): PreferenceStore
}

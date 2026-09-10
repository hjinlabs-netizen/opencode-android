package com.anomalyco.opencode.di

import com.anomalyco.opencode.data.settings.PreferenceStore
import com.anomalyco.opencode.data.settings.SharedPreferenceStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the plain (unencrypted) preference store used by [com.anomalyco.opencode.domain.repository.SettingsRepository].
 * Separate from the encrypted server-token store on purpose.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SettingsModule {

    @Binds
    @Singleton
    abstract fun bindPreferenceStore(impl: SharedPreferenceStore): PreferenceStore
}

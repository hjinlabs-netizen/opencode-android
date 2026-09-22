package com.anomalyco.opencode.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.anomalyco.opencode.data.settings.PreferenceStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides the single [DataStore] instance backing the plain (unencrypted)
 * [PreferenceStore]. Created via the factory (not the top-level
 * `preferencesDataStore` delegate) so it can be injected as a `@Singleton`
 * and shared by every [PreferenceStore] consumer. One DataStore per file name
 * is a hard DataStore requirement — this provider is the only place it is built.
 */
@Module
@InstallIn(SingletonComponent::class)
object PreferenceStorageModule {

    @Provides
    @Singleton
    fun providePreferencesDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create {
        context.preferencesDataStoreFile(STORE_NAME)
    }

    private const val STORE_NAME = "opencode_prefs"
}

package com.anomalyco.opencode.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/**
 * Provides a single application-lifetime [CoroutineScope] shared by all
 * long-running data-layer components (the SSE supervisor, the settings
 * watcher). A [SupervisorJob] ensures one failing child never tears down the
 * others; children run on [Dispatchers.Default]/IO as they choose internally.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

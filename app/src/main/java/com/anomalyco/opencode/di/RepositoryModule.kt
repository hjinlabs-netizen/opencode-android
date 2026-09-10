package com.anomalyco.opencode.di

import com.anomalyco.opencode.domain.repository.ChatStreamRepository
import com.anomalyco.opencode.domain.repository.ConnectionRepository
import com.anomalyco.opencode.domain.repository.SessionRepository
import com.anomalyco.opencode.data.repository.ChatStreamRepositoryImpl
import com.anomalyco.opencode.data.repository.ConnectionRepositoryImpl
import com.anomalyco.opencode.data.repository.SessionRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds repository interfaces to their concrete implementations.
 * Kept separate from [NetworkModule] so swapping a fake in tests is one line.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindConnectionRepository(impl: ConnectionRepositoryImpl): ConnectionRepository

    @Binds
    @Singleton
    abstract fun bindSessionRepository(impl: SessionRepositoryImpl): SessionRepository

    @Binds
    @Singleton
    abstract fun bindChatStreamRepository(impl: ChatStreamRepositoryImpl): ChatStreamRepository
}

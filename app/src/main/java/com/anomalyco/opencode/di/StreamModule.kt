package com.anomalyco.opencode.di

import com.anomalyco.opencode.data.remote.stream.EventTransport
import com.anomalyco.opencode.data.remote.stream.SseEventTransport
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the raw event-feed transport. Swap to a WebSocket implementation by
 * changing this single line — nothing else in the data layer knows the
 * protocol.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class StreamModule {

    @Binds
    @Singleton
    abstract fun bindEventTransport(impl: SseEventTransport): EventTransport
}

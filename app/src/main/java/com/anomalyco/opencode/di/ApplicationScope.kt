package com.anomalyco.opencode.di

import javax.inject.Qualifier

/**
 * Marks the application-lifetime [kotlinx.coroutines.CoroutineScope].
 * Work launched here lives as long as the process (used by long-running
 * singletons like the event-stream supervisor and settings watchers).
 */
@Retention(AnnotationRetention.BINARY)
@Qualifier
annotation class ApplicationScope

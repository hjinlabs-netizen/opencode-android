package com.anomalyco.opencode.data.repository

import com.anomalyco.opencode.data.remote.OpenCodeApi
import com.anomalyco.opencode.data.settings.SecureSettingsStore
import com.anomalyco.opencode.domain.model.HealthInfo
import com.anomalyco.opencode.domain.model.ServerConfig
import com.anomalyco.opencode.domain.repository.ConnectionRepository
import kotlinx.coroutines.flow.Flow
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLException

/**
 * Concrete [ConnectionRepository]. Bridges the encrypted settings store and
 * the Ktor-backed API, translating low-level transport exceptions into
 * human-readable failures.
 */
@Singleton
class ConnectionRepositoryImpl @Inject constructor(
    private val settings: SecureSettingsStore,
    private val api: OpenCodeApi,
) : ConnectionRepository {

    override val config: Flow<ServerConfig?> = settings.config

    override suspend fun saveConfig(config: ServerConfig) = settings.save(config)

    override suspend fun clearConfig() = settings.clear()

    override suspend fun checkHealth(config: ServerConfig): Result<HealthInfo> =
        runCatching { api.health(config.normalizedUrl, config.token) }
            .recoverCatching { throw it.toFriendlyException() }
}

/** Map common transport failures to messages that make sense in the UI. */
private fun Throwable.toFriendlyException(): Exception = when (this) {
    is UnknownHostException ->
        Exception("Sunucu adresi çözümlenemedi — URL ve internet bağlantısını kontrol et.", this)
    is ConnectException ->
        Exception("Sunucuya bağlanılamadı — OpenCode servisinin çalıştığından emin ol.", this)
    is SocketTimeoutException ->
        Exception("Bağlantı zaman aşımına uğradı — sunucu yavaş veya erişilemez.", this)
    is SSLException ->
        Exception("Güvenli bağlantı kurulamadı — sertifikayı ve HTTPS adresini kontrol et.", this)
    else -> Exception(message ?: "Bilinmeyen bir hata oluştu.", this)
}

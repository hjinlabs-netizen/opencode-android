package com.anomalyco.opencode.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.anomalyco.opencode.domain.model.ServerConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encrypted wrapper around [SharedPreferences] for the server configuration.
 *
 * Values are encrypted at rest with a key held in the Android Keystore.
 * Exposes a [Flow] so ViewModels react to configuration changes.
 *
 * The initial load happens asynchronously on a private IO scope; until it
 * completes the flow emits `null` ("no configuration yet"), which is safe
 * because a fresh install has no configuration anyway.
 */
@Singleton
class SecureSettingsStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** In-memory cache so reads stay cheap and the flow can emit without disk I/O. */
    private val _config = MutableStateFlow<ServerConfig?>(null)
    val config: Flow<ServerConfig?> = _config.asStateFlow()

    /** Encrypted prefs creation is expensive (Keystore) so it is lazy and off-main. */
    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    init {
        scope.launch { load() }
    }

    /** Reads the persisted config (first access triggers lazy prefs creation). */
    private suspend fun load(): ServerConfig? = withContext(Dispatchers.IO) {
        val url = prefs.getString(KEY_URL, null) ?: return@withContext null
        val token = prefs.getString(KEY_TOKEN, "").orEmpty()
        ServerConfig(baseUrl = url, token = token)
    }.also { _config.value = it }

    /** Atomically persist and publish a new configuration. */
    suspend fun save(config: ServerConfig) = mutex.withLock {
        withContext(Dispatchers.IO) {
            prefs.edit()
                .putString(KEY_URL, config.normalizedUrl)
                .putString(KEY_TOKEN, config.token)
                .apply()
            _config.value = config
        }
    }

    /** Remove the stored configuration and reset the flow to `null`. */
    suspend fun clear() = mutex.withLock {
        withContext(Dispatchers.IO) {
            prefs.edit().clear().apply()
            _config.value = null
        }
    }

    private companion object {
        const val FILE_NAME = "opencode_secure_prefs"
        const val KEY_URL = "server_url"
        const val KEY_TOKEN = "server_token"
    }
}

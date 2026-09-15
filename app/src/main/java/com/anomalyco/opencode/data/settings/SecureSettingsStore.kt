package com.anomalyco.opencode.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.anomalyco.opencode.domain.model.ServerConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
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
 * Persistent storage for the server configuration (Sprint 1d).
 *
 * The token is protected by a first-party AndroidKeyStore AES-GCM envelope
 * ([KeystoreAesGcmCipher]) stored under a single vault key ([ConfigVault]) in
 * an ordinary prefs file — the VALUE is already authenticated ciphertext, so
 * the container no longer needs the deprecated `EncryptedSharedPreferences`
 * machinery. The public API is unchanged from the pre-1d implementation;
 * existing installs are transparently migrated off `EncryptedSharedPreferences`
 * by [ConfigVault]'s idempotent, crash-safe migration on first load.
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

    /** Synchronous snapshot of the cached config (null during the very first load). */
    val current: ServerConfig?
        get() = _config.value

    /** Lazy so a fresh-install process that never touches creds pays nothing. */
    private val vault: ConfigVault by lazy {
        ConfigVault(
            vault = PreferencesVault(
                context.getSharedPreferences(VAULT_FILE, Context.MODE_PRIVATE),
            ),
            legacy = EncryptedLegacyStore(context),
            cipher = KeystoreAesGcmCipher(),
        )
    }

    init {
        scope.launch { load() }
    }

    /**
     * Reads (and, once, migrates) the persisted config. Fails closed to
     * "unconfigured" — a Keystore or disk fault must never crash the startup
     * path the way lazy `EncryptedSharedPreferences.create` used to.
     */
    private suspend fun load(): ServerConfig? = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching { vault.load() }.getOrNull()
        }
    }.also { _config.value = it }

    /** Atomically persist and publish a new configuration. */
    suspend fun save(config: ServerConfig) = mutex.withLock {
        withContext(Dispatchers.IO) { vault.save(config) }
        _config.value = config
    }

    /** Remove the stored configuration (both generations) and reset to `null`. */
    suspend fun clear() = mutex.withLock {
        withContext(Dispatchers.IO) { runCatching { vault.clear() } }
        _config.value = null
    }

    private companion object {
        const val VAULT_FILE = "opencode_vault_prefs"
    }
}

/** Plain SharedPreferences behind the [KeyValueSink] contract (values are envelopes). */
private class PreferencesVault(
    private val prefs: SharedPreferences,
) : KeyValueSink {
    override fun read(key: String): String? = prefs.getString(key, null)

    override fun write(key: String, value: String) {
        prefs.edit { putString(key, value) }
    }

    override fun erase(key: String) {
        prefs.edit { remove(key) }
    }
}

/**
 * The pre-1d storage generation: `EncryptedSharedPreferences` under
 * `opencode_secure_prefs` (keys unchanged from the Phase 1 layout). Read
 * only after the backing FILE exists — fresh installs never create the
 * Keystore master key or the file — and destroyed by the vault engine once
 * the encrypted copy is verified. A legacy file that itself fails to open
 * (the known Tink/Keystore breakage) reads as "nothing to migrate" so the
 * app can always move forward to the new vault.
 */
private class EncryptedLegacyStore(
    private val context: Context,
) : LegacyConfigSource {

    private val prefs: SharedPreferences? by lazy {
        runCatching {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                LEGACY_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrNull()
    }

    override fun exists(): Boolean = legacyFile().exists() || legacyBackupFile().exists()

    override fun read(): StoredServerConfig? = runCatching {
        val store = prefs ?: return@runCatching null
        val url = store.getString(KEY_URL, null) ?: return@runCatching null
        StoredServerConfig(url = url, token = store.getString(KEY_TOKEN, "").orEmpty())
    }.getOrNull()

    override fun destroy() {
        runCatching { context.deleteSharedPreferences(LEGACY_FILE) }
        runCatching { legacyFile().delete() }
        runCatching { legacyBackupFile().delete() }
    }

    private fun legacyFile(): File = File(context.dataDir, "shared_prefs/$LEGACY_FILE.xml")

    private fun legacyBackupFile(): File =
        File(context.dataDir, "shared_prefs/$LEGACY_FILE.xml.bak")

    private companion object {
        const val LEGACY_FILE = "opencode_secure_prefs"
        const val KEY_URL = "server_url"
        const val KEY_TOKEN = "server_token"
    }
}

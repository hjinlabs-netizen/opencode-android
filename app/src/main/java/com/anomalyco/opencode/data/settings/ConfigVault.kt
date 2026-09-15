package com.anomalyco.opencode.data.settings

import com.anomalyco.opencode.domain.model.ServerConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Raw string persistence for the vault file (production: plain SharedPreferences — the VALUES are already Keystore-encrypted envelopes). */
interface KeyValueSink {
    fun read(key: String): String?
    fun write(key: String, value: String)
    fun erase(key: String)
}

/** Bridge to the pre-1d `EncryptedSharedPreferences` file being migrated away from. */
interface LegacyConfigSource {
    /** Cheap existence check; must not touch the Keystore on fresh installs. */
    fun exists(): Boolean

    /** @return the stored config, or null when absent/unreadable (a corrupt legacy file is treated as "nothing to migrate"). */
    fun read(): StoredServerConfig?

    /** Permanently removes the legacy file. */
    fun destroy()
}

/** The encrypted payload: the whole connection config as ONE atomic envelope, so url+token can never migrate half-way. */
@Serializable
data class StoredServerConfig(val url: String, val token: String = "") {
    fun toDomain(): ServerConfig = ServerConfig(baseUrl = url, token = token)
}

/**
 * Sprint 1d migration engine — pure Kotlin, no Android imports, so the whole
 * lifecycle is unit-testable on the JVM:
 *
 *  - **fresh install**: nothing exists; `load` returns null, the legacy store
 *    is never even opened; `save` writes the first Keystore envelope directly.
 *  - **existing user (migration)**: legacy value -> `TokenCipher.encrypt` ->
 *    vault -> READ-BACK VERIFICATION -> legacy destroyed. The legacy file is
 *    removed only after the encrypted copy is durably readable (crash-safe:
 *    an interruption between write and destroy just re-migrates on the next
 *    launch; a failed write never destroys the legacy source).
 *  - **idempotent**: once the vault holds an entry, the legacy store is only
 *    cleaned up if still present — repeated loads never re-read it.
 *  - **fail closed**: a vault entry that no longer authenticates (key lost,
 *    corrupted data) surfaces as "unconfigured" without throwing and without
 *    wiping anything until the user saves again.
 *
 * After a successful migration no plaintext (or legacy) copy of the token
 * survives; the only persisted form is the versioned AES-GCM envelope.
 */
class ConfigVault(
    private val vault: KeyValueSink,
    private val legacy: LegacyConfigSource,
    private val cipher: TokenCipher,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    /** @return the active config, running the one-time migration first when needed. */
    fun load(): ServerConfig? {
        val stored = vault.read(KEY_CONFIG)
        if (stored != null) {
            // Vault is the source of truth; sweep a legacy file that an
            // interrupted cleanup may have left behind (idempotent).
            cleanupLegacy()
            return decrypt(stored)?.toDomain()
        }
        if (!legacy.exists()) return null
        // Defensive: a legacy implementation that violates its "null when
        // unreadable" contract (e.g. the known Tink master-key breakage
        // throwing outright) must never block startup — it just means there
        // is nothing safe to migrate and the file gets swept.
        val fromLegacy = runCatching { legacy.read() }.getOrNull()
        if (fromLegacy == null) {
            cleanupLegacy()
            return null
        }
        val envelope = encrypt(fromLegacy)
        vault.write(KEY_CONFIG, envelope)
        // Crash-safe checkpoint: destroy the legacy source only when the
        // encrypted copy is verifiably readable from disk.
        if (vault.read(KEY_CONFIG) == envelope && decrypt(envelope) != null) {
            cleanupLegacy()
        }
        return fromLegacy.toDomain()
    }

    /** Encrypts and persists [config]; any surviving legacy copy is retired. */
    fun save(config: ServerConfig) {
        vault.write(
            KEY_CONFIG,
            encrypt(StoredServerConfig(config.normalizedUrl, config.token)),
        )
        cleanupLegacy()
    }

    /** Forget everything: vault entry and legacy file alike. */
    fun clear() {
        vault.erase(KEY_CONFIG)
        cleanupLegacy()
    }

    private fun cleanupLegacy() {
        if (legacy.exists()) legacy.destroy()
    }

    private fun encrypt(value: StoredServerConfig): String =
        cipher.encrypt(json.encodeToString(StoredServerConfig.serializer(), value))

    private fun decrypt(envelope: String): StoredServerConfig? = runCatching {
        cipher.decrypt(envelope)?.let {
            json.decodeFromString(StoredServerConfig.serializer(), it)
        }
    }.getOrNull()

    companion object {
        const val KEY_CONFIG = "server_config_v1"
    }
}

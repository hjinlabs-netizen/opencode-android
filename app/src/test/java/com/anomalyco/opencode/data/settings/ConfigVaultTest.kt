package com.anomalyco.opencode.data.settings

import com.anomalyco.opencode.domain.model.ServerConfig
import java.io.IOException
import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sprint 1d migration-engine tests: fresh installs, the one-release legacy
 * migration (idempotent, crash-safe, retryable) and every failure mode the
 * security requirements call out (missing key, corrupted data, interrupted
 * migration).
 */
class ConfigVaultTest {

    private val vault = FakeVaultSink()
    private val legacy = FakeLegacySource()
    private val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private val cipher = SoftwareAesGcmCipher(key)
    private fun engine() = ConfigVault(vault, legacy, cipher)

    private val config = ServerConfig("http://192.168.1.10:4096", "s3cr3t-token")

    // ---- fresh install --------------------------------------------------------

    @Test
    fun `a fresh install loads nothing and never opens the legacy store`() {
        val loaded = engine().load()

        assertNull(loaded)
        assertFalse(vault.data.containsKey(ConfigVault.KEY_CONFIG))
        assertEquals("fresh installs must not pay for the legacy Keystore path", 0, legacy.reads)
        assertEquals(0, legacy.destroys)
    }

    @Test
    fun `a save on a fresh install persists only an encrypted envelope`() {
        engine().save(config)

        val raw = requireNotNull(vault.raw)
        assertTrue("expected a v1 envelope, got: $raw", raw.startsWith(AesGcmEnvelope.VERSION_PREFIX))
        assertFalse("no plaintext token may touch disk", raw.contains("s3cr3t-token"))
        assertFalse("the URL is encrypted at rest too, matching the old store", raw.contains("192.168.1.10"))
        assertEquals(config, engine().load())
    }

    @Test
    fun `save stores the normalized url`() {
        engine().save(ServerConfig("http://host:1/", "t"))

        assertEquals("http://host:1", requireNotNull(engine().load()).baseUrl)
    }

    // ---- existing-user migration ----------------------------------------------

    @Test
    fun `a legacy config migrates to the vault and the legacy file is destroyed`() {
        legacy.existsFlag = true
        legacy.config = StoredServerConfig(config.baseUrl, config.token)

        assertEquals(config, engine().load())

        val raw = requireNotNull(vault.raw)
        assertTrue(raw.startsWith(AesGcmEnvelope.VERSION_PREFIX))
        assertFalse("no plaintext survives the migration", raw.contains("s3cr3t-token"))
        assertEquals(1, legacy.destroys)
        assertFalse(legacy.existsFlag)
    }

    @Test
    fun `migration is idempotent across repeated loads`() {
        legacy.existsFlag = true
        legacy.config = StoredServerConfig(config.baseUrl, config.token)

        val first = engine().load()
        val second = engine().load()
        val third = engine().load()

        assertEquals(first, second)
        assertEquals(second, third)
        assertEquals("legacy must be read exactly once, ever", 1, legacy.reads)
        assertEquals("the sweep after migration is idempotent", 1, legacy.destroys)
    }

    @Test
    fun `an empty legacy file is swept without producing a config`() {
        legacy.existsFlag = true
        legacy.config = null

        assertNull(engine().load())
        assertEquals(1, legacy.destroys)
    }

    @Test
    fun `a corrupt legacy file is swept so startup can move forward`() {
        legacy.existsFlag = true
        legacy.readThrows = true

        assertNull(engine().load())
        assertEquals(1, legacy.destroys)
    }

    @Test
    fun `the vault is the source of truth over a stray legacy copy`() {
        engine().save(config)
        legacy.existsFlag = true // an interrupted cleanup left the old file behind
        legacy.config = StoredServerConfig("http://stale", "stale-token")

        assertEquals(config, engine().load())
        assertEquals(1, legacy.destroys)
    }

    // ---- crash safety / retryability -------------------------------------------

    @Test
    fun `an interrupted migration keeps the legacy source and retries cleanly`() {
        legacy.existsFlag = true
        legacy.config = StoredServerConfig(config.baseUrl, config.token)
        vault.failNextWrites = 1 // crash between encrypting and persisting

        val thrown = runCatching { engine().load() }.exceptionOrNull()
        assertTrue(thrown is IOException)
        assertEquals("the legacy source must survive a failed write", 0, legacy.destroys)

        // Next launch: the retry completes and retires the legacy file.
        assertEquals(config, engine().load())
        assertTrue(requireNotNull(vault.raw).startsWith(AesGcmEnvelope.VERSION_PREFIX))
        assertEquals(1, legacy.destroys)
    }

    @Test
    fun `a write that cannot be verified back does not retire the legacy source`() {
        legacy.existsFlag = true
        legacy.config = StoredServerConfig(config.baseUrl, config.token)
        vault.poisonNextReads = 1 // the persisted bytes are not what was written

        assertEquals("the user still gets their config this launch", config, engine().load())
        assertEquals("an unverified vault copy must NOT destroy the fallback", 0, legacy.destroys)
    }

    // ---- failure cases -----------------------------------------------------------

    @Test
    fun `a corrupted vault entry fails closed as unconfigured without throwing`() {
        engine().save(config)
        vault.raw = vault.raw?.reversed() // corrupt on disk

        assertNull(engine().load())
    }

    @Test
    fun `a lost keystore key fails closed as unconfigured`() {
        engine().save(config)
        val afterKeyLoss = ConfigVault(vault, legacy, SoftwareAesGcmCipher(ByteArray(32)))

        assertNull(afterKeyLoss.load())
    }

    @Test
    fun `a key loss heals on the next save and retires the vault remnants`() {
        engine().save(config)
        val healed = ConfigVault(vault, legacy, SoftwareAesGcmCipher(ByteArray(32)))
        assertNull(healed.load())

        healed.save(ServerConfig("http://new:2", "fresh"))
        assertEquals("http://new:2", requireNotNull(healed.load()).baseUrl)
    }

    @Test
    fun `clear forgets every generation`() {
        legacy.existsFlag = true
        legacy.config = StoredServerConfig("http://old:1", "old-token")
        engine().load() // migrate
        engine().save(config)

        engine().clear()

        assertNull(vault.raw)
        assertNull(engine().load())
        assertFalse(legacy.existsFlag)
    }

    // ---- fakes ---------------------------------------------------------------------

    private class FakeVaultSink : KeyValueSink {
        val data = LinkedHashMap<String, String>()
        var failNextWrites = 0
        var poisonNextReads = 0

        var raw: String?
            get() = data[ConfigVault.KEY_CONFIG]
            set(value) {
                if (value == null) data.remove(ConfigVault.KEY_CONFIG) else data[ConfigVault.KEY_CONFIG] = value
            }

        override fun read(key: String): String? {
            val value = data[key]
            if (poisonNextReads > 0 && value != null) {
                poisonNextReads--
                return value.reversed()
            }
            return value
        }

        override fun write(key: String, value: String) {
            if (failNextWrites > 0) {
                failNextWrites--
                throw IOException("simulated interrupted write")
            }
            data[key] = value
        }

        override fun erase(key: String) {
            data.remove(key)
        }
    }

    private class FakeLegacySource : LegacyConfigSource {
        var existsFlag = false
        var config: StoredServerConfig? = null
        var readThrows = false
        var reads = 0
        var destroys = 0

        override fun exists(): Boolean = existsFlag

        override fun read(): StoredServerConfig? {
            reads++
            if (readThrows) throw IllegalStateException("simulated broken keystore file")
            return config
        }

        override fun destroy() {
            destroys++
            existsFlag = false
            config = null
        }
    }
}

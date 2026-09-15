package com.anomalyco.opencode.data.settings

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.anomalyco.opencode.domain.model.ServerConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sprint 1d CI device regression (CI-lane hardening): exercises the REAL
 * `AndroidKeyStore` provider end-to-end — the only production code path the
 * JVM suite structurally cannot reach. Deliberately NOT faking the Keystore:
 * [KeystoreAesGcmCipher] with the production key alias runs against the
 * platform's own key storage, IV randomization and GCM tag verification.
 *
 * Isolation: the persistence test writes ONLY to a test-owned prefs
 * namespace (`keystore_vault_device_test`) — the production vault file
 * (`opencode_vault_prefs`) is never touched, so nightly runs cannot disturb
 * user state even on a shared device. Fixtures only; nothing here is a real
 * credential, and nothing sensitive is ever logged.
 *
 * Snake_case method names: DEX < 040 (minSdk 26) forbids spaces in method
 * names (same constraint discovered by the 1c.3 dexing gate).
 */
class KeystoreVaultAndroidTest {

    private val context: Context =
        InstrumentationRegistry.getInstrumentation().targetContext

    private val cipher = KeystoreAesGcmCipher()

    private val fixture = ServerConfig(
        baseUrl = "http://fixture.invalid:8080",
        token = "device-fixture-token-0123456789abcdef",
    )

    @After
    fun tearDown() {
        context.deleteSharedPreferences(TEST_PREFS)
    }

    /** Test 1 — real Keystore round trip, with provider-enforced IV freshness. */
    @Test
    fun keystore_round_trip_preserves_exact_plaintext() {
        val plaintext = """{"url":"${fixture.baseUrl}","token":"${fixture.token}"} — ünïcödé ✓ 🔐"""

        val first = cipher.encrypt(plaintext)
        val second = cipher.encrypt(plaintext)

        assertEquals(plaintext, cipher.decrypt(first))
        assertEquals(plaintext, cipher.decrypt(second))
        assertNotEquals(
            "the AndroidKeyStore provider must randomize the IV per operation",
            first,
            second,
        )
    }

    /** Test 2 — documented envelope contract: v1. prefix, valid structure, no plaintext. */
    @Test
    fun envelope_follows_the_v1_contract_and_hides_plaintext() {
        val envelope = cipher.encrypt(fixture.token)

        assertTrue("expected v1. prefix", envelope.startsWith(AesGcmEnvelope.VERSION_PREFIX))
        val (iv, body) = requireNotNull(AesGcmEnvelope.decode(envelope)) { "envelope must decode" }
        assertEquals("GCM IV is 12 bytes", AesGcmEnvelope.IV_BYTES, iv.size)
        assertTrue(
            "body must at least contain the 16-byte authentication tag",
            body.size >= TAG_BYTES,
        )
        assertFalse("plaintext must never appear inside the envelope", envelope.contains(fixture.token))
    }

    /** Test 3 — tampering fails closed: null, never a crash, never plaintext. */
    @Test
    fun tampered_envelopes_fail_closed() {
        val envelope = cipher.encrypt(fixture.token)
        val (iv, body) = requireNotNull(AesGcmEnvelope.decode(envelope)) { "envelope must decode" }

        val flipped = body.copyOf().also {
            it[body.size / 2] = (it[body.size / 2].toInt() xor 0x01).toByte()
        }
        assertNull("flipped ciphertext bit must fail authentication", cipher.decrypt(AesGcmEnvelope.encode(iv, flipped)))

        val tagTruncated = body.copyOfRange(0, body.size - TAG_BYTES)
        assertNull("missing tag must fail authentication", cipher.decrypt(AesGcmEnvelope.encode(iv, tagTruncated)))

        assertNull(cipher.decrypt("v1.###not-valid-base64###"))
        assertNull(cipher.decrypt("v9.AAAA"))
    }

    /** Test 4 — production vault path over Android storage + the real Keystore. */
    @Test
    fun vault_save_load_clear_round_trips_through_keystore() {
        val sink = TestPrefsSink(context)
        val vault = ConfigVault(vault = sink, legacy = NoLegacySource, cipher = cipher)

        assertNull("clean namespace must start unconfigured", vault.load())

        vault.save(fixture)

        val raw = requireNotNull(sink.read(ConfigVault.KEY_CONFIG)) { "config must be persisted" }
        assertTrue("persisted form must be a v1 envelope", raw.startsWith(AesGcmEnvelope.VERSION_PREFIX))
        assertFalse("no plaintext token may reach disk", raw.contains(fixture.token))
        assertFalse("no plaintext url may reach disk", raw.contains("fixture.invalid"))

        val loaded = requireNotNull(vault.load()) { "vault must load the config back" }
        assertEquals(fixture.normalizedUrl, loaded.baseUrl)
        assertEquals(fixture.token, loaded.token)

        vault.clear()
        assertNull(sink.read(ConfigVault.KEY_CONFIG))
        assertNull("after clear the vault is unconfigured again", vault.load())
    }

    /** Real SharedPreferences persistence under a test-owned namespace. */
    private class TestPrefsSink(context: Context) : KeyValueSink {
        private val prefs = context.getSharedPreferences(TEST_PREFS, Context.MODE_PRIVATE)

        override fun read(key: String): String? = prefs.getString(key, null)

        override fun write(key: String, value: String) {
            prefs.edit().putString(key, value).commit()
        }

        override fun erase(key: String) {
            prefs.edit().remove(key).commit()
        }
    }

    /** Fresh-install simulation: the legacy generation never exists here. */
    private object NoLegacySource : LegacyConfigSource {
        override fun exists(): Boolean = false
        override fun read(): StoredServerConfig? = null
        override fun destroy() = Unit
    }

    private companion object {
        const val TEST_PREFS = "keystore_vault_device_test"
        const val TAG_BYTES = 16
    }
}

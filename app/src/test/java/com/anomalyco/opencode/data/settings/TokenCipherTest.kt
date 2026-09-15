package com.anomalyco.opencode.data.settings

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sprint 1d crypto contract tests. The cipher under test is REAL AES-256-GCM
 * (`SoftwareAesGcmCipher` below) sharing the production envelope format —
 * only the key SOURCE differs from the Keystore implementation, so the
 * format, IV-uniqueness and authentication-tag behavior are verified
 * end-to-end on the JVM.
 */
class TokenCipherTest {

    private fun newCipher(): TokenCipher =
        SoftwareAesGcmCipher(SecureRandom().let { rnd -> ByteArray(32).also(rnd::nextBytes) })

    @Test
    fun `envelope round-trips iv and body`() {
        val iv = ByteArray(AesGcmEnvelope.IV_BYTES) { it.toByte() }
        val body = byteArrayOf(9, 8, 7, 6)
        val encoded = AesGcmEnvelope.encode(iv, body)

        assertTrue(encoded.startsWith(AesGcmEnvelope.VERSION_PREFIX))
        assertEquals(iv.toList() to body.toList(), AesGcmEnvelope.decode(encoded)?.let { (a, b) -> a.toList() to b.toList() })
    }

    @Test
    fun `envelope rejects wrong version and malformed input`() {
        assertNull(AesGcmEnvelope.decode("v2.AAAA"))
        assertNull(AesGcmEnvelope.decode("v1.!!!not base64!!!"))
        assertNull(AesGcmEnvelope.decode("v1." + Base64.getEncoder().encodeToString(ByteArray(12))))
        assertNull(AesGcmEnvelope.decode("garbage"))
    }

    @Test
    fun `the same plaintext encrypts to different ciphertexts every time`() {
        val cipher = newCipher()
        val first = cipher.encrypt("hunter2-token")
        val second = cipher.encrypt("hunter2-token")

        assertNotEquals(
            "a repeated IV under GCM is a catastrophic nonce reuse",
            first,
            second,
        )
        assertEquals("hunter2-token", cipher.decrypt(first))
        assertEquals("hunter2-token", cipher.decrypt(second))
    }

    @Test
    fun `decryption restores the original including multi-byte text`() {
        val cipher = newCipher()
        val text = "pässwörd 🔐 日本語 — ${"a".repeat(1024)}"
        assertEquals(text, cipher.decrypt(cipher.encrypt(text)))
    }

    @Test
    fun `a flipped ciphertext bit fails authentication safely`() {
        val cipher = newCipher()
        val envelope = cipher.encrypt("secret-value")
        val (iv, body) = requireNotNull(AesGcmEnvelope.decode(envelope))
        body[body.size / 2] = (body[body.size / 2].toInt() xor 0x01).toByte()

        assertNull(cipher.decrypt(AesGcmEnvelope.encode(iv, body)))
    }

    @Test
    fun `a tag swap between envelopes fails authentication`() {
        // GCM's tag covers IV+ciphertext+AAD: transplanting one message's body
        // onto another's IV must fail, not silently mis-decode.
        val cipher = newCipher()
        val (ivA, bodyA) = requireNotNull(AesGcmEnvelope.decode(cipher.encrypt("aaaa")))
        val (ivB, _) = requireNotNull(AesGcmEnvelope.decode(cipher.encrypt("bbbb")))

        assertNull(cipher.decrypt(AesGcmEnvelope.encode(ivB, bodyA)))
    }

    @Test
    fun `the wrong key fails closed without throwing`() {
        val encryptor = newCipher()
        val stranger = newCipher()

        assertNull(stranger.decrypt(encryptor.encrypt("secret")))
    }
}

/**
 * A real AES-GCM cipher for the JVM: identical transformation, envelope
 * format and fail-closed contract as [KeystoreAesGcmCipher]; only the key
 * lives in memory instead of the AndroidKeyStore.
 */
internal class SoftwareAesGcmCipher(key: ByteArray) : TokenCipher {

    private val keySpec = SecretKeySpec(key, "AES")
    private val random = SecureRandom()

    override fun encrypt(plaintext: String): String {
        val iv = ByteArray(AesGcmEnvelope.IV_BYTES)
        random.nextBytes(iv)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(AesGcmEnvelope.TAG_BITS, iv))
        return AesGcmEnvelope.encode(iv, cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)))
    }

    override fun decrypt(envelope: String): String? {
        val (iv, body) = AesGcmEnvelope.decode(envelope) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(AesGcmEnvelope.TAG_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        }.getOrNull()
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

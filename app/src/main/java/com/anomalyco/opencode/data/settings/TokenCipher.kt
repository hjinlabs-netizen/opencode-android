package com.anomalyco.opencode.data.settings

import java.util.Base64

/**
 * Sprint 1d: authenticated encryption for the server token. The vault stores
 * ONLY ciphertexts produced here — no plaintext credential ever reaches disk.
 *
 * Implementations must use a fresh random IV for every [encrypt] call (so the
 * same plaintext never repeats a ciphertext) and must fail CLOSED — returning
 * `null`, never throwing — on any authentication failure (tampered ciphertext,
 * wrong/bad-tag key input, malformed envelope, lost Keystore key).
 */
interface TokenCipher {
    fun encrypt(plaintext: String): String

    /** @return the original text, or `null` when authentication fails. */
    fun decrypt(envelope: String): String?
}

/**
 * The wire format of a [TokenCipher] output: `v1.` + Base64(IV || GCM
 * ciphertext || 128-bit tag). The version prefix leaves room for a future
 * key/algorithm rotation without ambiguity; decoding is pure and shared by
 * the Keystore implementation and the JVM test cipher so the format itself
 * is unit-tested.
 */
object AesGcmEnvelope {

    const val VERSION_PREFIX = "v1."

    /** GCM's standard IV length; the provider generates it fresh per call. */
    const val IV_BYTES = 12

    /** Authentication tag size in bits — full strength, tag verified on decrypt. */
    const val TAG_BITS = 128

    fun encode(iv: ByteArray, cipherTextWithTag: ByteArray): String =
        VERSION_PREFIX + Base64.getEncoder().encodeToString(iv + cipherTextWithTag)

    /** @return `(iv, cipherTextWithTag)`, or null for any malformed envelope. */
    fun decode(envelope: String): Pair<ByteArray, ByteArray>? {
        if (!envelope.startsWith(VERSION_PREFIX)) return null
        val bytes = runCatching {
            Base64.getDecoder().decode(envelope.substring(VERSION_PREFIX.length))
        }.getOrNull() ?: return null
        if (bytes.size <= IV_BYTES) return null
        return bytes.copyOfRange(0, IV_BYTES) to
            bytes.copyOfRange(IV_BYTES, bytes.size)
    }
}

package com.anomalyco.opencode.data.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Sprint 1d production cipher: AES-256-GCM with the key held in the
 * [AndroidKeyStore](https://developer.android.com/training/articles/keystore)
 * — the raw key bytes never enter this process, the key is non-exportable by
 * construction, and the Keystore provider refuses to reuse an IV: every
 * [encrypt] initializes the cipher WITHOUT a caller-supplied IV so the
 * provider generates a fresh one (read back via `cipher.iv`, per GCM spec
 * 12 bytes). Decryption verifies the 128-bit GCM tag; tampering or a
 * key-lost/reset Keystore fails closed to `null`.
 *
 * This replaces the deprecated `EncryptedSharedPreferences` mechanism
 * (androidx security-crypto / Tink, frozen at alpha06) with first-party
 * crypto on the same Keystore foundation.
 */
class KeystoreAesGcmCipher(
    private val alias: String = KEY_ALIAS,
) : TokenCipher {

    /** Lazy so construction never touches the Keystore provider. */
    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    override fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, obtainKey())
        val iv = checkNotNull(cipher.iv) { "the GCM provider must supply an IV" }
        val body = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return AesGcmEnvelope.encode(iv, body)
    }

    override fun decrypt(envelope: String): String? {
        val (iv, body) = AesGcmEnvelope.decode(envelope) ?: return null
        val key = keyStore.getKey(alias, null) as? SecretKey ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(AesGcmEnvelope.TAG_BITS, iv),
            )
            String(cipher.doFinal(body), Charsets.UTF_8)
        }.getOrNull() // AEADBadTagException / provider errors -> unconfigured
    }

    /** The Keystore key is created on first use and persists across installs. */
    private fun obtainKey(): SecretKey {
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        const val KEY_ALIAS = "com.hjinlabs.opencode.server-token.aes"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

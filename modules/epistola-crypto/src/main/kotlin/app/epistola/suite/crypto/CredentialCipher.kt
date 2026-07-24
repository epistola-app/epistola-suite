// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.crypto

import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Thrown when encryption or decryption fails (misconfigured key, tampered ciphertext, unknown key id). */
class EncryptionException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Authenticated AES-256-GCM encryption for credentials at rest.
 *
 * - A fresh random 96-bit nonce is generated per encryption.
 * - The 128-bit GCM tag authenticates the ciphertext; the key id is bound as AAD.
 * - Encryption always uses the [primaryKeyId]; decryption selects the key by the
 *   id embedded in the [CredentialEnvelope], so older keys keep working during
 *   rotation.
 * - [decrypt] passes non-envelope (legacy plaintext) values through unchanged.
 *
 * When [enabled] is false the cipher is a pure pass-through in both directions.
 *
 * Constructed via [CredentialCipherFactory]; do not instantiate directly outside
 * that factory and tests.
 */
class CredentialCipher internal constructor(
    /** Whether encryption is active; when false the cipher is a pass-through in both directions. */
    val enabled: Boolean,
    private val keys: Map<String, SecretKeySpec>,
    val primaryKeyId: String,
) {
    private val random = SecureRandom()

    fun encrypt(plaintext: String): String {
        if (!enabled) return plaintext
        val key = keys[primaryKeyId]
            ?: throw EncryptionException("No encryption key for primary id '$primaryKeyId'")
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(primaryKeyId.toByteArray(StandardCharsets.UTF_8))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        return CredentialEnvelope.format(primaryKeyId, nonce + ciphertext)
    }

    fun decrypt(raw: String): String {
        if (!enabled || !CredentialEnvelope.isEnvelope(raw)) return raw
        val parsed = CredentialEnvelope.parse(raw)
        val key = keys[parsed.keyId]
            ?: throw EncryptionException("No decryption key for id '${parsed.keyId}'")
        if (parsed.payload.size <= NONCE_BYTES) {
            throw EncryptionException("Malformed ciphertext payload (too short)")
        }
        val nonce = parsed.payload.copyOfRange(0, NONCE_BYTES)
        val ciphertext = parsed.payload.copyOfRange(NONCE_BYTES, parsed.payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(parsed.keyId.toByteArray(StandardCharsets.UTF_8))
        return try {
            String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
        } catch (e: AEADBadTagException) {
            throw EncryptionException("Credential decryption failed (tampered ciphertext or wrong key)", e)
        }
    }

    /**
     * The key id [raw] is encrypted under, or null if it is plaintext. Used by the
     * rotation backfill to decide whether a value needs re-encryption under the
     * current [primaryKeyId].
     */
    fun keyIdOf(raw: String): String? = if (CredentialEnvelope.isEnvelope(raw)) CredentialEnvelope.parse(raw).keyId else null

    /** Round-trips a probe through the primary key to prove the JCE provider works. Throws on failure. */
    internal fun selfTest() {
        if (!enabled) return
        val probe = "epistola-encryption-self-test"
        check(decrypt(encrypt(probe)) == probe) { "Encryption self-test failed: round-trip mismatch" }
    }

    companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val NONCE_BYTES = 12 // 96-bit nonce, the GCM-recommended size
        const val TAG_BITS = 128 // 16-byte authentication tag
        const val KEY_BYTES = 32 // 256-bit AES key
    }
}

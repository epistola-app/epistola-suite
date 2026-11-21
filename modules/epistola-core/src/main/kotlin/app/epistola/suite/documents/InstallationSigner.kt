// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents

import app.epistola.suite.metadata.AppMetadataService
import app.epistola.suite.metadata.getAs
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Signs the short-lived tokens of document generation with one key per installation (ADR 0055 §3):
 * the interactive form's link codes, sessions and guards, and the web UI's job tokens.
 *
 * Every kind of token passes a purpose of its own, which goes into the MAC before the payload, so a
 * value signed for one purpose never verifies for another. A new kind of token takes a new purpose.
 *
 * The key is random, made the first time it is needed and kept in `app_metadata`, shared by every
 * node. It is not encrypted: anyone who can read the database already reads the data these tokens
 * lead to, and they live minutes to days. Deleting the row rotates it, and every token in flight
 * stops working.
 */
@Component
class InstallationSigner(private val appMetadata: AppMetadataService) {
    private val random = SecureRandom()

    private val key: ByteArray by lazy {
        appMetadata.setIfAbsent(SIGNING_KEY, Base64.getEncoder().encodeToString(ByteArray(KEY_BYTES).also(random::nextBytes)))
        Base64.getDecoder().decode(requireNotNull(appMetadata.getAs<String>(SIGNING_KEY)) { "The installation signing key was not stored" })
    }

    /** The MAC over [payload] for [purpose]. */
    fun mac(purpose: String, payload: ByteArray): ByteArray = Mac.getInstance(HMAC).run {
        init(SecretKeySpec(key, HMAC))
        update(purpose.toByteArray(Charsets.US_ASCII))
        doFinal(payload)
    }

    /** [size] random bytes, for a nonce. */
    fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    companion object {
        // The name it was first stored under, kept so an installation keeps its key.
        private const val SIGNING_KEY = "interactive_generation.form_link_key"
        private const val HMAC = "HmacSHA256"
        private const val KEY_BYTES = 32

        /** [bytes] as unpadded URL-safe base64, as tokens are written. */
        fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        /** [part] decoded from URL-safe base64, or null when it is not. */
        fun decode(part: String): ByteArray? = runCatching { Base64.getUrlDecoder().decode(part) }.getOrNull()
    }
}

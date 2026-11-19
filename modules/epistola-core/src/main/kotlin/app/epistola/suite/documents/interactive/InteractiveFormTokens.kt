// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents.interactive

import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat

/** A freshly minted form token: the value for the link, and the hash it is found by. */
data class MintedFormToken(val token: String, val hash: String)

/**
 * The form link's token (contract ADR 0011, "The URL is a capability"): 256 random bits, base64url,
 * carrying nothing but itself. Stored twice: as its SHA-256, to find the form by, and encrypted, so
 * `getGenerationJobStatus` can hand the link out again while the request waits.
 *
 * Everything that makes, finds or hands out a token goes through here, so moving to short-lived
 * links later (epistola-contract#134) changes this class and the form's entry route only.
 */
@Component
class InteractiveFormTokens(private val properties: InteractiveGenerationProperties) {
    private val random = SecureRandom()

    fun mint(): MintedFormToken {
        val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        return MintedFormToken(token, hash(token))
    }

    /** The hash a token is stored and found by. */
    fun hash(token: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.US_ASCII)))

    /** Whether [token] can be one of ours at all, before it is looked up. */
    fun isWellFormed(token: String): Boolean = token.length == TOKEN_LENGTH && token.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    /** The form's address for [token]. */
    fun link(token: String): String {
        val base = requireNotNull(properties.publicBaseUrl) { "epistola.generation.interactive.public-base-url is not set" }
        return "${base.trimEnd('/')}/forms/$token"
    }

    private companion object {
        const val TOKEN_BYTES = 32
        const val TOKEN_LENGTH = 43 // base64url of 32 bytes, unpadded
    }
}

// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents

import app.epistola.suite.common.ids.GenerationRequestKey
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.documents.interactive.InteractiveFormLinks
import app.epistola.suite.time.EpistolaClock
import org.springframework.stereotype.Component
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID

/**
 * Tokens that let one person follow one generation job they started in the web UI, and download
 * its document: signed by this installation over the request, the person's user id and an expiry.
 *
 * A person in the UI may hold `DOCUMENT_VIEW`, which reads every document in the tenant, including
 * the ones applications generated. The UI's job routes therefore never take a bare request id: they
 * take a token, and answer only when it is valid, unexpired and names the signed-in person. A token
 * that leaks (browser history, the form's return URL, a screenshot) is of no use to anyone else.
 *
 * Signed with the form links' key ([InteractiveFormLinks]) under a purpose of its own, so a form
 * link code or session is never accepted as a job token, nor the other way round. Deleting that key
 * rotates both. Nothing is stored per token.
 */
@Component
class GenerationJobTokens(private val formLinks: InteractiveFormLinks) {

    /** A token for [user] to follow [requestId] until [expiresAt]. */
    fun issue(requestId: GenerationRequestKey, user: UserKey, expiresAt: OffsetDateTime): String {
        val payload = ByteBuffer.allocate(PAYLOAD_BYTES)
            .putLong(requestId.value.mostSignificantBits)
            .putLong(requestId.value.leastSignificantBits)
            .putLong(user.value.mostSignificantBits)
            .putLong(user.value.leastSignificantBits)
            .putLong(expiresAt.toEpochSecond())
            .array()
        return "${b64(payload)}.${b64(formLinks.mac(PURPOSE, payload))}"
    }

    /** The request [token] lets [user] follow, or null when it is forged, expired or someone else's. */
    fun verify(token: String, user: UserKey): GenerationRequestKey? {
        val parts = token.split('.')
        if (parts.size != 2 || token.length > MAX_TOKEN_LENGTH) return null
        val payload = decode(parts[0])?.takeIf { it.size == PAYLOAD_BYTES } ?: return null
        val signature = decode(parts[1]) ?: return null
        if (!MessageDigest.isEqual(formLinks.mac(PURPOSE, payload), signature)) return null
        val buffer = ByteBuffer.wrap(payload)
        val requestId = UUID(buffer.long, buffer.long)
        if (UUID(buffer.long, buffer.long) != user.value) return null
        if (buffer.long <= EpistolaClock.instant().epochSecond) return null
        return GenerationRequestKey(requestId)
    }

    private fun decode(part: String): ByteArray? = runCatching { Base64.getUrlDecoder().decode(part) }.getOrNull()

    private fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private companion object {
        const val PURPOSE = "epistola.generation-job-token.v1:"
        const val PAYLOAD_BYTES = 16 + 16 + 8
        const val MAX_TOKEN_LENGTH = 200
    }
}

// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents.interactive

import app.epistola.suite.common.ids.GenerationRequestKey
import app.epistola.suite.metadata.AppMetadataService
import app.epistola.suite.metadata.getAs
import app.epistola.suite.time.EpistolaClock
import org.jdbi.v3.core.Handle
import org.springframework.stereotype.Component
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** A link to a form, as a caller is handed it: where to send the browser, and until when it opens. */
data class IssuedFormLink(val url: String, val expiresAt: OffsetDateTime)

/** A person's session on a form, opened by redeeming a link: the cookie value and until when it lasts. */
data class FormSession(val token: String, val expiresAt: OffsetDateTime)

/**
 * The form's links and sessions (contract ADR 0011, "The URL is a capability", amended in 2.1.1 by
 * epistola-contract#134).
 *
 * - A **link** is `{public-base-url}/forms/{requestId}?authCode={code}`. The code is signed by this
 *   installation over the request, its own expiry and a nonce. It opens the form until
 *   `link-lifetime` has passed (never past the request's wait), and once: redeeming it records the
 *   nonce. Nothing is stored when a link is handed out, so `getGenerationJobStatus` can hand out a
 *   fresh one on every call without writing.
 * - A **session** is what redeeming a link starts: a signed token over the request and an expiry
 *   `session-idle-timeout` away (never past the request's wait), renewed on every request the
 *   person makes. The form page keeps it in a cookie scoped to the form's path.
 *
 * Neither carries anything but the request id: no tenant, no caller, no data. Whether the form can
 * still be used is always the request's status, so nothing here needs revoking.
 *
 * The signing key is random, made the first time it is needed and kept in `app_metadata`, shared by
 * every node. It is not encrypted: anyone who can read the database already reads the data a form
 * shows, and the key signs only links that live minutes. Deleting the row rotates it; links and
 * sessions in flight then stop working, and the person asks the caller for a fresh link.
 */
@Component
class InteractiveFormLinks(
    private val properties: InteractiveGenerationProperties,
    private val appMetadata: AppMetadataService,
) {
    private val random = SecureRandom()

    private val key: ByteArray by lazy {
        appMetadata.setIfAbsent(SIGNING_KEY, Base64.getEncoder().encodeToString(ByteArray(KEY_BYTES).also(random::nextBytes)))
        Base64.getDecoder().decode(requireNotNull(appMetadata.getAs<String>(SIGNING_KEY)) { "The form link signing key was not stored" })
    }

    /** A fresh link to the form of [requestId], whose wait ends at [waitEndsAt]. */
    fun issue(requestId: GenerationRequestKey, waitEndsAt: OffsetDateTime): IssuedFormLink {
        val expiresAt = minOf(EpistolaClock.offsetDateTime().plus(properties.linkLifetime), waitEndsAt)
        val code = sign(LINK, requestId, expiresAt, nonce())
        val base = requireNotNull(properties.publicBaseUrl) { "epistola.generation.interactive.public-base-url is not set" }
        return IssuedFormLink("${base.trimEnd('/')}/forms/${requestId.value}?authCode=$code", expiresAt)
    }

    /**
     * Whether [code] is a link to [requestId] that has not expired and was not redeemed before; if
     * so, it is redeemed now, in [handle]'s transaction. A code works once.
     */
    fun redeem(handle: Handle, requestId: GenerationRequestKey, code: String): Boolean {
        val claims = verify(LINK, requestId, code) ?: return false
        return handle.createUpdate("INSERT INTO generation_form_link_redemptions (nonce, expires_at) VALUES (:nonce, :expiresAt) ON CONFLICT DO NOTHING")
            .bind("nonce", claims.nonce)
            .bind("expiresAt", claims.expiresAt)
            .execute() == 1
    }

    /** A session on the form of [requestId], lasting `session-idle-timeout` from now, never past [waitEndsAt]. */
    fun openSession(requestId: GenerationRequestKey, waitEndsAt: OffsetDateTime): FormSession {
        val expiresAt = minOf(EpistolaClock.offsetDateTime().plus(properties.sessionIdleTimeout), waitEndsAt)
        return FormSession(sign(SESSION, requestId, expiresAt, nonce()), expiresAt)
    }

    /** Whether [token] is a session on the form of [requestId] that has not expired. */
    fun isSession(requestId: GenerationRequestKey, token: String): Boolean = verify(SESSION, requestId, token) != null

    /**
     * The anti-forgery value every post on [requestId]'s form carries. The session cookie is
     * `SameSite=Lax`, so another site's post never carries it; the guard, signed by this installation
     * and only ever written into the form's own page, is a second line another site cannot forge. It
     * depends on the form, not the session, so a page rendered in one tab still posts after another
     * tab renewed the session.
     */
    fun formGuard(requestId: GenerationRequestKey): String = b64(mac(GUARD, requestId.value.toString().toByteArray(Charsets.US_ASCII)))

    fun isFormGuard(requestId: GenerationRequestKey, guard: String): Boolean = MessageDigest.isEqual(formGuard(requestId).toByteArray(), guard.toByteArray())

    private data class Claims(val expiresAt: OffsetDateTime, val nonce: String)

    private fun sign(purpose: String, requestId: GenerationRequestKey, expiresAt: OffsetDateTime, nonce: ByteArray): String {
        val payload = ByteBuffer.allocate(PAYLOAD_BYTES)
            .putLong(requestId.value.mostSignificantBits)
            .putLong(requestId.value.leastSignificantBits)
            .putLong(expiresAt.toEpochSecond())
            .put(nonce)
            .array()
        return "${b64(payload)}.${b64(mac(purpose, payload))}"
    }

    private fun verify(purpose: String, requestId: GenerationRequestKey, value: String): Claims? {
        val parts = value.split('.')
        if (parts.size != 2 || value.length > MAX_VALUE_LENGTH) return null
        val payload = runCatching { Base64.getUrlDecoder().decode(parts[0]) }.getOrNull()?.takeIf { it.size == PAYLOAD_BYTES } ?: return null
        val signature = runCatching { Base64.getUrlDecoder().decode(parts[1]) }.getOrNull() ?: return null
        if (!MessageDigest.isEqual(mac(purpose, payload), signature)) return null
        val buffer = ByteBuffer.wrap(payload)
        if (UUID(buffer.long, buffer.long) != requestId.value) return null
        val expiresAt = OffsetDateTime.ofInstant(java.time.Instant.ofEpochSecond(buffer.long), ZoneOffset.UTC)
        if (!expiresAt.isAfter(EpistolaClock.offsetDateTime())) return null
        val nonce = ByteArray(NONCE_BYTES).also(buffer::get)
        return Claims(expiresAt, b64(nonce))
    }

    /**
     * This installation's MAC over [payload] for [purpose]. Shared with [app.epistola.suite.documents.GenerationJobTokens],
     * which signs with the same key under a purpose of its own, so no value is ever accepted for another.
     */
    internal fun mac(purpose: String, payload: ByteArray): ByteArray = Mac.getInstance(HMAC).run {
        init(SecretKeySpec(key, HMAC))
        update(purpose.toByteArray(Charsets.US_ASCII))
        doFinal(payload)
    }

    private fun nonce(): ByteArray = ByteArray(NONCE_BYTES).also(random::nextBytes)

    private fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private companion object {
        const val SIGNING_KEY = "interactive_generation.form_link_key"
        const val HMAC = "HmacSHA256"
        const val KEY_BYTES = 32
        const val NONCE_BYTES = 16
        const val PAYLOAD_BYTES = 16 + 8 + NONCE_BYTES
        const val MAX_VALUE_LENGTH = 200

        // Domain separation: a link code is never accepted as a session, or the other way round.
        const val LINK = "epistola.form-link.v1:"
        const val SESSION = "epistola.form-session.v1:"
        const val GUARD = "epistola.form-guard.v1:"
    }
}

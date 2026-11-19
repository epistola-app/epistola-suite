// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents.interactive

import app.epistola.suite.common.ids.GenerationRequestKey
import app.epistola.suite.documents.model.CancelReason
import app.epistola.suite.documents.model.DocumentGenerationRequest
import app.epistola.suite.documents.model.GENERATION_REQUEST_COLUMNS
import app.epistola.suite.documents.model.RequestStatus
import app.epistola.suite.templates.validation.MissingDataField
import app.epistola.suite.time.EpistolaClock
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.kotlin.mapTo
import org.jdbi.v3.json.Json
import org.springframework.stereotype.Component
import tools.jackson.databind.node.ObjectNode
import java.time.OffsetDateTime

/** How a form that can no longer be filled in ended. */
enum class FormOutcome {
    /** Submitted: the request is on its way (or done). */
    SUBMITTED,

    /** Cancelled, by the person or the caller. */
    CANCELLED,

    /** Nobody submitted it before its link expired. */
    EXPIRED,
}

/**
 * A request's form as found by its token: the request, and what the caller said about the form.
 * [expired] compares [expiresAt] with the application clock at lookup, so a form is closed the moment
 * its link expires, whether or not the sweep has cancelled the request yet.
 */
data class FoundForm(
    val request: DocumentGenerationRequest,
    val trigger: InteractiveTrigger,
    val expiresAt: OffsetDateTime,
    val expired: Boolean,
    val returnUrl: String?,
    val title: String?,
    val instructions: String?,
    val links: List<InteractiveLink>,
    val locale: String?,
    val missingFields: List<MissingDataField>,
    val callerData: ObjectNode,
) {
    /** Whether the person can still fill it in and submit it. */
    val open: Boolean get() = request.status == RequestStatus.AWAITING_INPUT && !expired

    /** How it ended, when it is not [open]. */
    val outcome: FormOutcome?
        get() = when {
            open -> null
            request.status == RequestStatus.CANCELLED && request.cancelReason != CancelReason.EXPIRED -> FormOutcome.CANCELLED
            request.status == RequestStatus.CANCELLED || request.status == RequestStatus.AWAITING_INPUT -> FormOutcome.EXPIRED
            else -> FormOutcome.SUBMITTED
        }
}

/**
 * Finds a form by the token in its link. The token is the only authority the form page has
 * (contract ADR 0011, "The URL is a capability"), so the lookup is by its hash and nothing else: no
 * tenant, no principal. A malformed token is not looked up at all.
 */
@Component
class InteractiveForms(private val formTokens: InteractiveFormTokens) {

    /** The form for [token], or null when there is none. [forUpdate] locks the request row for a state change. */
    fun find(handle: Handle, token: String, forUpdate: Boolean = false): FoundForm? {
        if (!formTokens.isWellFormed(token)) return null
        val row = handle.createQuery(
            """
            SELECT request_id, created_at, trigger, expires_at, expires_at <= :now AS expired, return_url, title, instructions,
                   links, locale, missing_fields, caller_data
            FROM generation_request_interactions
            WHERE token_hash = :hash
            """,
        )
            .bind("hash", formTokens.hash(token))
            .bind("now", EpistolaClock.offsetDateTime())
            .mapTo<FormRow>()
            .findOne()
            .orElse(null) ?: return null
        val request = handle.createQuery(
            """
            SELECT $GENERATION_REQUEST_COLUMNS
            FROM document_generation_requests
            WHERE id = :id AND created_at = :createdAt
            ${if (forUpdate) "FOR UPDATE" else ""}
            """,
        )
            .bind("id", row.requestId)
            .bind("createdAt", row.createdAt)
            .mapTo<DocumentGenerationRequest>()
            .findOne()
            .orElse(null) ?: return null
        return FoundForm(
            request = request,
            trigger = InteractiveTrigger.valueOf(row.trigger),
            expiresAt = row.expiresAt,
            expired = row.expired,
            returnUrl = row.returnUrl,
            title = row.title,
            instructions = row.instructions,
            links = row.links,
            locale = row.locale,
            missingFields = row.missingFields,
            callerData = row.callerData,
        )
    }

    internal data class FormRow(
        val requestId: GenerationRequestKey,
        val createdAt: OffsetDateTime,
        val trigger: String,
        val expiresAt: OffsetDateTime,
        val expired: Boolean,
        val returnUrl: String?,
        val title: String?,
        val instructions: String?,
        @Json val links: List<InteractiveLink>,
        val locale: String?,
        @Json val missingFields: List<MissingDataField>,
        @Json val callerData: ObjectNode,
    )
}

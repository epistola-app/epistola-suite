// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents.interactive

import app.epistola.suite.documents.model.DocumentGenerationRequest
import app.epistola.suite.documents.model.RequestStatus
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.templates.validation.MissingDataField
import app.epistola.suite.time.EpistolaClock
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.kotlin.mapTo
import org.jdbi.v3.json.Json
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Attaches the form of each request created with `interactive` to the requests a query read, in
 * one round trip. With [withLinks], a request awaiting input gets a fresh, short-lived link
 * (epistola-contract#134): the job status hands one out on every call; a listing hands out none,
 * so a caller polling a list does not collect live links it never uses.
 *
 * A link controls the form: whoever opens it submits the data the document renders from. So it goes
 * only to the caller that asked ([callerId] as recorded at acceptance), never to another reader of
 * the job: `DOCUMENT_VIEW` reads every job of the tenant for a person, a bearer token or an
 * application that sees every document (ADR 0055 §8).
 */
@Component
class InteractiveStates(private val formLinks: InteractiveFormLinks) {

    fun attach(handle: Handle, requests: List<DocumentGenerationRequest>, withLinks: Boolean): List<DocumentGenerationRequest> {
        // A request without a form costs no lookup: by request id it would probe every partition.
        val withForm = requests.filter { it.hasForm }
        if (withForm.isEmpty()) return requests
        val rows = handle.createQuery(
            """
            SELECT request_id, trigger, expires_at, missing_fields, edited_fields, requested_by
            FROM generation_request_interactions
            WHERE request_id = ANY(:ids)
            """,
        )
            .bindArray("ids", UUID::class.java, *withForm.map { it.id.value }.toTypedArray())
            .mapTo<InteractionRow>()
            .associateBy { it.requestId }
        val caller = callerId()
        return requests.map { request ->
            val row = rows[request.id.value] ?: return@map request
            val link = row.expiresAt
                ?.takeIf { withLinks && request.status == RequestStatus.AWAITING_INPUT && it.isAfter(EpistolaClock.offsetDateTime()) }
                ?.takeIf { caller != null && caller == row.requestedBy }
                ?.let { formLinks.issue(request.id, it) }
            request.copy(
                interactive = InteractiveState(
                    trigger = InteractiveTrigger.valueOf(row.trigger),
                    url = link?.url,
                    expiresAt = row.expiresAt,
                    missingFields = row.missingFields,
                    editedFields = row.editedFields,
                    urlExpiresAt = link?.expiresAt,
                ),
            )
        }
    }

    internal data class InteractionRow(
        val requestId: UUID,
        val trigger: String,
        val expiresAt: OffsetDateTime?,
        @Json val missingFields: List<MissingDataField>,
        @Json val editedFields: List<String>?,
        val requestedBy: String?,
    )

    companion object {
        /**
         * Who the current caller is, as a request records who asked: its application, so another key of
         * the same application is the same caller (ADR 0054), or its own user id without one. Null with
         * no principal.
         */
        fun callerId(): String? = SecurityContext.currentOrNull()?.collectConsumerId()
    }
}

// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents.interactive

import app.epistola.suite.documents.model.DocumentGenerationRequest
import app.epistola.suite.documents.model.RequestStatus
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
 */
@Component
class InteractiveStates(private val formLinks: InteractiveFormLinks) {

    fun attach(handle: Handle, requests: List<DocumentGenerationRequest>, withLinks: Boolean): List<DocumentGenerationRequest> {
        if (requests.isEmpty()) return requests
        val rows = handle.createQuery(
            """
            SELECT request_id, trigger, expires_at, missing_fields, edited_fields
            FROM generation_request_interactions
            WHERE request_id = ANY(:ids)
            """,
        )
            .bindArray("ids", UUID::class.java, *requests.map { it.id.value }.toTypedArray())
            .mapTo<InteractionRow>()
            .associateBy { it.requestId }
        return requests.map { request ->
            val row = rows[request.id.value] ?: return@map request
            val link = row.expiresAt
                ?.takeIf { withLinks && request.status == RequestStatus.AWAITING_INPUT && it.isAfter(EpistolaClock.offsetDateTime()) }
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
    )
}

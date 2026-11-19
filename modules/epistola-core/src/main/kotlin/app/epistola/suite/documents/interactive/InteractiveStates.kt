// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents.interactive

import app.epistola.suite.crypto.Secret
import app.epistola.suite.documents.model.DocumentGenerationRequest
import app.epistola.suite.documents.model.RequestStatus
import app.epistola.suite.templates.validation.MissingDataField
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.kotlin.mapTo
import org.jdbi.v3.json.Json
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Attaches the form of each request created with `interactive` to the requests a query read, in
 * one round trip. The link is handed out again only while a request is awaiting input (contract
 * ADR 0011: "Fetched, not stored").
 */
@Component
class InteractiveStates(private val formTokens: InteractiveFormTokens) {

    fun attach(handle: Handle, requests: List<DocumentGenerationRequest>): List<DocumentGenerationRequest> {
        if (requests.isEmpty()) return requests
        val rows = handle.createQuery(
            """
            SELECT request_id, trigger, token_encrypted, expires_at, missing_fields, edited_fields
            FROM generation_request_interactions
            WHERE request_id = ANY(:ids)
            """,
        )
            .bindArray("ids", UUID::class.java, *requests.map { it.id.value }.toTypedArray())
            .mapTo<InteractionRow>()
            .associateBy { it.requestId }
        return requests.map { request ->
            val row = rows[request.id.value] ?: return@map request
            request.copy(
                interactive = InteractiveState(
                    trigger = InteractiveTrigger.valueOf(row.trigger),
                    url = row.tokenEncrypted?.takeIf { request.status == RequestStatus.AWAITING_INPUT }?.let { formTokens.link(it.value) },
                    expiresAt = row.expiresAt,
                    missingFields = row.missingFields,
                    editedFields = row.editedFields,
                ),
            )
        }
    }

    fun attach(handle: Handle, request: DocumentGenerationRequest): DocumentGenerationRequest = attach(handle, listOf(request)).single()

    internal data class InteractionRow(
        val requestId: UUID,
        val trigger: String,
        val tokenEncrypted: Secret?,
        val expiresAt: OffsetDateTime?,
        @Json val missingFields: List<MissingDataField>,
        @Json val editedFields: List<String>?,
    )
}

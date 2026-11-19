// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents.commands

import app.epistola.suite.documents.model.CancelReason
import app.epistola.suite.documents.model.DocumentGenerationRequest
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.security.SystemInternal
import app.epistola.suite.time.EpistolaClock
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Cancels, with [CancelReason.EXPIRED], up to [limit] requests whose form nobody submitted before its
 * link expired (contract ADR 0011), and emits their `cancelled` collect results. Returns how many.
 *
 * [SystemInternal]: run by the expiry sweep, a background task with no principal, over every tenant.
 */
data class ExpireInteractiveForms(val limit: Int = 500) :
    Command<Int>,
    SystemInternal

@Component
class ExpireInteractiveFormsHandler(
    private val jdbi: Jdbi,
    private val mediator: Mediator,
) : CommandHandler<ExpireInteractiveForms, Int> {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun handle(command: ExpireInteractiveForms): Int = jdbi.inTransaction<Int, Exception> { handle ->
        // The application clock decides, as it set the expiry the caller was told and as the form page
        // compares it. SKIP LOCKED leaves a form being submitted right now to that submission.
        val expired = handle.createQuery(
            """
            WITH due AS (
                SELECT q.id, q.created_at
                FROM generation_request_interactions i
                JOIN document_generation_requests q ON q.id = i.request_id AND q.created_at = i.created_at
                WHERE q.status = 'AWAITING_INPUT' AND i.expires_at <= :now
                ORDER BY i.expires_at
                LIMIT :limit
                FOR UPDATE OF q SKIP LOCKED
            )
            UPDATE document_generation_requests r
            SET status = 'CANCELLED', cancel_reason = 'EXPIRED', completed_at = NOW()
            FROM due
            WHERE r.id = due.id AND r.created_at = due.created_at AND r.status = 'AWAITING_INPUT'
            RETURNING r.id, r.batch_id, r.tenant_key, r.catalog_key, r.registry, r.namespace, r.template_key, r.variant_key, r.version_key,
                      r.environment_key, r.release_version, r.draft_id, r.draft_label, r.catalog_version_id, r.recoveries, r.data, r.filename,
                      r.correlation_id, r.routing_key, r.document_key, r.status, r.claimed_by, r.claimed_at, r.error_message, r.created_at,
                      r.started_at, r.completed_at, r.expires_at, r.cancel_reason, r.submitted_at
            """,
        )
            .bind("limit", command.limit)
            .bind("now", EpistolaClock.offsetDateTime())
            .mapTo<DocumentGenerationRequest>()
            .list()
        expired.forEach { emitCancelledIfInteractive(handle, mediator, it, CancelReason.EXPIRED) }
        // A redeemed link's nonce is only needed until the link would have expired anyway.
        handle.createUpdate("DELETE FROM generation_form_link_redemptions WHERE expires_at <= :now")
            .bind("now", EpistolaClock.offsetDateTime())
            .execute()
        if (expired.isNotEmpty()) log.info("Expired interactive forms count={}", expired.size)
        expired.size
    }
}

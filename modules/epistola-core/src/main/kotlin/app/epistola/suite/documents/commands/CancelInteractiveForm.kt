// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents.commands

import app.epistola.suite.common.ids.GenerationRequestKey
import app.epistola.suite.documents.interactive.FormOutcome
import app.epistola.suite.documents.interactive.InteractiveForms
import app.epistola.suite.documents.interactive.ReturnUrls
import app.epistola.suite.documents.model.CancelReason
import app.epistola.suite.documents.model.DocumentGenerationRequest
import app.epistola.suite.documents.model.GENERATION_REQUEST_COLUMNS
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.security.SystemInternal
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** What cancelling a form came to. */
sealed interface CancelFormOutcome {
    /** Cancelled by the person. [redirectUrl] is the caller's `returnUrl` with the outcome added, or null. */
    data class Cancelled(val requestId: GenerationRequestKey, val redirectUrl: String?) : CancelFormOutcome

    /** The form had already ended. */
    data class Closed(val outcome: FormOutcome, val returnUrl: String?) : CancelFormOutcome
}

/**
 * The person declines the form (contract ADR 0011): the request is cancelled with
 * [CancelReason.USER], and the caller learns it from a `cancelled` collect result. What the person
 * typed was never stored, so nothing of it is kept.
 *
 * [SystemInternal]: the token is the authority, as for [SubmitInteractiveForm].
 */
data class CancelInteractiveForm(val token: String) :
    Command<CancelFormOutcome?>,
    SystemInternal {
    override fun toString(): String = "CancelInteractiveForm(token=***)"
}

@Component
class CancelInteractiveFormHandler(
    private val jdbi: Jdbi,
    private val forms: InteractiveForms,
    private val mediator: Mediator,
) : CommandHandler<CancelInteractiveForm, CancelFormOutcome?> {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun handle(command: CancelInteractiveForm): CancelFormOutcome? = jdbi.inTransaction<CancelFormOutcome?, Exception> { handle ->
        val form = forms.find(handle, command.token, forUpdate = true) ?: return@inTransaction null
        form.outcome?.let { return@inTransaction CancelFormOutcome.Closed(it, form.returnUrl) }
        val cancelled = handle.createQuery(
            """
            UPDATE document_generation_requests
            SET status = 'CANCELLED', cancel_reason = 'USER', completed_at = NOW()
            WHERE id = :id AND created_at = :createdAt AND status = 'AWAITING_INPUT'
            RETURNING $GENERATION_REQUEST_COLUMNS
            """,
        )
            .bind("id", form.request.id)
            .bind("createdAt", form.request.createdAt)
            .mapTo<DocumentGenerationRequest>()
            .one()
        emitCancelledIfInteractive(handle, mediator, cancelled, CancelReason.USER)
        log.info("Interactive form cancelled by the person request={}", cancelled.id)
        CancelFormOutcome.Cancelled(cancelled.id, form.returnUrl?.let { ReturnUrls.withOutcome(it, cancelled.id, "cancelled") })
    }
}

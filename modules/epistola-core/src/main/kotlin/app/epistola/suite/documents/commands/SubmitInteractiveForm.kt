// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents.commands

import app.epistola.suite.common.ids.GenerationRequestKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.documents.interactive.BoundDataContract
import app.epistola.suite.documents.interactive.EditedFields
import app.epistola.suite.documents.interactive.FormOutcome
import app.epistola.suite.documents.interactive.InteractiveForms
import app.epistola.suite.documents.interactive.ReturnUrls
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.SystemInternal
import app.epistola.suite.templates.validation.TemplateDataAnalysis
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/** What submitting a form came to. */
sealed interface SubmitFormOutcome {
    /** The request is now pending. [redirectUrl] is the caller's `returnUrl` with the outcome added, or null. */
    data class Submitted(val requestId: GenerationRequestKey, val tenantKey: TenantKey, val redirectUrl: String?) : SubmitFormOutcome

    /** The data does not satisfy the contract; the form stays open and nothing changed. */
    data class Invalid(val analysis: TemplateDataAnalysis) : SubmitFormOutcome

    /** The form can no longer be submitted: submitted before (another tab, a replay), cancelled or expired. */
    data class Closed(val outcome: FormOutcome, val returnUrl: String?) : SubmitFormOutcome
}

/**
 * Submits a form (contract ADR 0011): validates [data] against the contract of the release the
 * request is bound to (on the server; the browser is not trusted), stores it as the request's data,
 * records where it differs from what the caller sent, and moves the request from `AWAITING_INPUT` to
 * `PENDING` so a worker picks it up like any other. Submitted once: the request row is locked and
 * the move is conditional, so a second submission finds the form closed.
 *
 * [SystemInternal]: the token is the authority, as for [app.epistola.suite.documents.queries.GetInteractiveForm].
 */
data class SubmitInteractiveForm(val token: String, val data: ObjectNode) :
    Command<SubmitFormOutcome?>,
    SystemInternal {
    override fun toString(): String = "SubmitInteractiveForm(token=***)"
}

@Component
class SubmitInteractiveFormHandler(
    private val jdbi: Jdbi,
    private val forms: InteractiveForms,
    private val boundContract: BoundDataContract,
    private val objectMapper: ObjectMapper,
) : CommandHandler<SubmitInteractiveForm, SubmitFormOutcome?> {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun handle(command: SubmitInteractiveForm): SubmitFormOutcome? = jdbi.inTransaction<SubmitFormOutcome?, Exception> { handle ->
        val form = forms.find(handle, command.token, forUpdate = true) ?: return@inTransaction null
        form.outcome?.let { return@inTransaction SubmitFormOutcome.Closed(it, form.returnUrl) }
        val request = form.request

        val analysis = boundContract.analyze(boundContract.of(request), command.data)
        if (!analysis.valid) return@inTransaction SubmitFormOutcome.Invalid(analysis)

        val edited = EditedFields.between(form.callerData, command.data)
        handle.createUpdate(
            """
            UPDATE document_generation_requests
            SET data = :data::jsonb, status = 'PENDING', submitted_at = NOW()
            WHERE id = :id AND created_at = :createdAt AND status = 'AWAITING_INPUT'
            """,
        )
            .bind("data", command.data.toString())
            .bind("id", request.id)
            .bind("createdAt", request.createdAt)
            .execute()
        handle.createUpdate("UPDATE generation_request_interactions SET edited_fields = :edited::jsonb WHERE request_id = :id AND created_at = :createdAt")
            .bind("edited", objectMapper.writeValueAsString(edited))
            .bind("id", request.id)
            .bind("createdAt", request.createdAt)
            .execute()

        log.info("Interactive form submitted request={} editedFields={}", request.id, edited.size)
        SubmitFormOutcome.Submitted(request.id, request.tenantKey, form.returnUrl?.let { ReturnUrls.withOutcome(it, request.id, "pending") })
    }
}

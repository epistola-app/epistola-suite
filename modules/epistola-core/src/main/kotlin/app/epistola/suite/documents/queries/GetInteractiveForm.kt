// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents.queries

import app.epistola.suite.common.ids.GenerationRequestKey
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.documents.interactive.BoundDataContract
import app.epistola.suite.documents.interactive.FormOutcome
import app.epistola.suite.documents.interactive.InteractiveForms
import app.epistola.suite.documents.interactive.InteractiveLink
import app.epistola.suite.documents.interactive.InteractiveTrigger
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.SystemInternal
import app.epistola.suite.templates.validation.MissingDataField
import app.epistola.suite.templates.validation.TemplateDataAnalysis
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.node.ObjectNode
import java.time.OffsetDateTime

/** What the form page shows for a token. */
sealed interface InteractiveFormView {
    val requestId: GenerationRequestKey
    val tenantKey: TenantKey

    /**
     * A form the person can fill in: the data contract of the release the request is bound to, the
     * data so far (the caller's), and what the caller said about it. [analysis] says what is still
     * missing or invalid in [data], for marking fields.
     */
    data class Open(
        override val requestId: GenerationRequestKey,
        override val tenantKey: TenantKey,
        val templateKey: TemplateKey,
        val trigger: InteractiveTrigger,
        val expiresAt: OffsetDateTime,
        val title: String?,
        val instructions: String?,
        val links: List<InteractiveLink>,
        val locale: String?,
        val returnUrl: String?,
        val dataModel: ObjectNode?,
        val data: ObjectNode,
        val missingFields: List<MissingDataField>,
        val analysis: TemplateDataAnalysis,
    ) : InteractiveFormView

    /** A form that can no longer be filled in, and how it ended. */
    data class Closed(
        override val requestId: GenerationRequestKey,
        override val tenantKey: TenantKey,
        val outcome: FormOutcome,
        val returnUrl: String?,
    ) : InteractiveFormView
}

/**
 * The form behind a link's [token] (contract ADR 0011), or null when no form has that token.
 *
 * [SystemInternal]: the person on the form has no Epistola account and is never authenticated. The
 * token is the authority, delegated by the caller that created the request, over this one request
 * and nothing else; it is looked up by its hash, never logged.
 */
data class GetInteractiveForm(val token: String) :
    Query<InteractiveFormView?>,
    SystemInternal {
    override fun toString(): String = "GetInteractiveForm(token=***)"
}

@Component
class GetInteractiveFormHandler(
    private val jdbi: Jdbi,
    private val forms: InteractiveForms,
    private val boundContract: BoundDataContract,
) : QueryHandler<GetInteractiveForm, InteractiveFormView?> {

    override fun handle(query: GetInteractiveForm): InteractiveFormView? {
        val form = jdbi.withHandle<_, Exception> { forms.find(it, query.token) } ?: return null
        val request = form.request
        val outcome = form.outcome
        if (outcome != null) return InteractiveFormView.Closed(request.id, request.tenantKey, outcome, form.returnUrl)
        val contract = boundContract.of(request)
        return InteractiveFormView.Open(
            requestId = request.id,
            tenantKey = request.tenantKey,
            templateKey = request.templateKey,
            trigger = form.trigger,
            expiresAt = form.expiresAt,
            title = form.title,
            instructions = form.instructions,
            links = form.links,
            locale = form.locale,
            returnUrl = form.returnUrl,
            dataModel = contract.dataModel,
            data = request.data,
            missingFields = form.missingFields,
            analysis = boundContract.analyze(contract, request.data),
        )
    }
}

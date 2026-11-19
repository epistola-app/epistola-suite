// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents.queries

import app.epistola.suite.common.ids.GenerationRequestKey
import app.epistola.suite.documents.interactive.BoundDataContract
import app.epistola.suite.documents.interactive.FormOutcome
import app.epistola.suite.documents.interactive.InteractiveFormLinks
import app.epistola.suite.documents.interactive.InteractiveFormPreview
import app.epistola.suite.documents.interactive.InteractiveForms
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.SystemInternal
import app.epistola.suite.templates.validation.TemplateDataAnalysis
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.node.ObjectNode

/** What a preview on a form came to. */
sealed interface FormPreviewOutcome {
    /** The PDF, as a worker would render it, with the preview watermark. */
    class Rendered(val pdf: ByteArray) : FormPreviewOutcome

    /** The data does not satisfy the contract yet: what is still missing or wrong. Nothing was rendered. */
    data class Invalid(val analysis: TemplateDataAnalysis) : FormPreviewOutcome

    /** This form is over its preview limits; try again shortly. */
    data object Limited : FormPreviewOutcome

    /** The form can no longer be filled in. */
    data class Closed(val outcome: FormOutcome) : FormPreviewOutcome
}

/**
 * A preview of the document [data] on [requestId]'s form would make, for a person holding [session]
 * (contract ADR 0011). Data that does not validate is not rendered, so a preview is always what
 * submitting would produce. Null when the session is not a live session on this form.
 *
 * [SystemInternal]: the session is the authority, as for [GetInteractiveForm].
 */
data class PreviewInteractiveForm(val requestId: GenerationRequestKey, val session: String, val data: ObjectNode) :
    Query<FormPreviewOutcome?>,
    SystemInternal {
    override fun toString(): String = "PreviewInteractiveForm(requestId=$requestId, session=***)"
}

@Component
class PreviewInteractiveFormHandler(
    private val jdbi: Jdbi,
    private val forms: InteractiveForms,
    private val links: InteractiveFormLinks,
    private val boundContract: BoundDataContract,
    private val preview: InteractiveFormPreview,
) : QueryHandler<PreviewInteractiveForm, FormPreviewOutcome?> {
    override fun handle(query: PreviewInteractiveForm): FormPreviewOutcome? {
        if (!links.isSession(query.requestId, query.session)) return null
        val form = jdbi.withHandle<_, Exception> { forms.find(it, query.requestId) } ?: return null
        form.outcome?.let { return FormPreviewOutcome.Closed(it) }
        val analysis = boundContract.analyze(boundContract.of(form.request), query.data)
        if (!analysis.valid) return FormPreviewOutcome.Invalid(analysis)
        return preview.render(form.request, query.data)?.let(FormPreviewOutcome::Rendered) ?: FormPreviewOutcome.Limited
    }
}

// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.validation

import app.epistola.suite.validation.ValidationException
import app.epistola.template.model.TemplateDocument
import org.springframework.stereotype.Component

/**
 * Applies the complete validation policy for documents crossing a persistence
 * or publication boundary. Graph validation runs first so subsequent validators
 * can safely traverse the document. This is also the boundary that maps
 * document-relative validation paths onto their containing request fields.
 */
@Component
class TemplateDocumentValidator(
    private val placeholderValidator: PlaceholderValidator,
    private val nodeParameterBindingValidator: NodeParameterBindingValidator,
    private val pageHeaderCardinalityValidator: PageHeaderCardinalityValidator,
) {
    private val graphValidator = TemplateDocumentGraphValidator()

    fun validateTemplate(doc: TemplateDocument) {
        validateTemplate(doc, requireCompleteBindings = true)
    }

    /**
     * Drafts may be persisted with required parameters still unbound. All other
     * document and binding validation remains active so a draft cannot retain
     * malformed expressions or bindings to parameters that do not exist.
     */
    fun validateTemplateDraft(doc: TemplateDocument) {
        validateTemplate(doc, requireCompleteBindings = false)
    }

    private fun validateTemplate(
        doc: TemplateDocument,
        requireCompleteBindings: Boolean,
    ) {
        validateAt(TEMPLATE_FIELD) {
            graphValidator.validate(doc)
            placeholderValidator.validateAsTemplate(doc)
            nodeParameterBindingValidator.validate(doc, requireCompleteBindings)
            pageHeaderCardinalityValidator.validate(doc)
        }
    }

    fun validateStencil(doc: TemplateDocument) {
        validateAt(STENCIL_FIELD) {
            graphValidator.validate(doc)
            placeholderValidator.validateAsStencilDefinition(doc)
        }
    }

    /**
     * Applies only traversal-safety checks to a template read from persistence.
     * Stored documents predate some semantic rules, but rendering must never
     * receive a malformed graph that can recurse or exhaust resources.
     */
    fun validateTemplateGraphForRendering(doc: TemplateDocument) {
        validateAt(TEMPLATE_FIELD) {
            graphValidator.validate(doc)
        }
    }

    private inline fun validateAt(requestField: String, validation: () -> Unit) {
        try {
            validation()
        } catch (exception: ValidationException) {
            throw ValidationException(
                field = "$requestField.${exception.field}",
                message = exception.message,
                code = exception.code,
            ).also { it.initCause(exception) }
        }
    }

    companion object {
        private const val TEMPLATE_FIELD = "templateModel"
        private const val STENCIL_FIELD = "content"
    }
}

// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.validation

import app.epistola.template.model.TemplateDocument
import org.springframework.stereotype.Component

/**
 * Applies the complete validation policy for documents crossing a persistence
 * or publication boundary. Graph validation runs first so subsequent validators
 * can safely traverse the document.
 */
@Component
class TemplateDocumentValidator(
    private val graphValidator: TemplateDocumentGraphValidator,
    private val placeholderValidator: PlaceholderValidator,
    private val nodeParameterBindingValidator: NodeParameterBindingValidator,
    private val pageHeaderCardinalityValidator: PageHeaderCardinalityValidator,
) {
    fun validateTemplate(doc: TemplateDocument) {
        graphValidator.validateTemplateDocument(doc)
        placeholderValidator.validateAsTemplate(doc, TEMPLATE_FIELD)
        nodeParameterBindingValidator.validate(doc, TEMPLATE_FIELD)
        pageHeaderCardinalityValidator.validate(doc, TEMPLATE_FIELD)
    }

    fun validateStencil(doc: TemplateDocument) {
        graphValidator.validateStencilDocument(doc)
        placeholderValidator.validateAsStencilDefinition(doc, STENCIL_FIELD)
    }

    companion object {
        private const val TEMPLATE_FIELD = "templateModel"
        private const val STENCIL_FIELD = "content"
    }
}

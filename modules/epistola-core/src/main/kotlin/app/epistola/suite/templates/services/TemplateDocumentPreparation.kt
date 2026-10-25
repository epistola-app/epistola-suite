// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.services

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.stencils.model.StencilInstances
import app.epistola.suite.templates.TemplateModels
import app.epistola.suite.templates.analysis.TemplatePathExtractor
import app.epistola.suite.templates.validation.TemplateDocumentValidator
import app.epistola.template.model.TemplateDocument
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

data class PreparedTemplateDocument(
    val templateModelJson: String,
    val referencedPaths: Set<String>,
    val referencedPathsJson: String,
)

/**
 * Produces all values derived from a template document for a database write.
 * Keeping these together prevents the stored model and its path index from
 * being updated independently.
 *
 * Every write stores stencil instances by reference (contract ADR 0006): a document that arrives
 * with a stencil's content inlined, as the editor edits it, is collapsed to references and fills
 * before it is validated and stored.
 */
@Component
class TemplateDocumentPreparation(
    private val objectMapper: ObjectMapper,
    private val pathExtractor: TemplatePathExtractor,
    private val validator: TemplateDocumentValidator,
) {
    fun prepare(document: TemplateDocument, catalogKey: CatalogKey): PreparedTemplateDocument {
        val stored = StencilInstances.collapse(document)
        validator.validateTemplate(stored)
        return prepareValidated(stored, catalogKey)
    }

    /** Prepare an editable draft, allowing required parameter bindings to be incomplete. */
    fun prepareDraft(document: TemplateDocument, catalogKey: CatalogKey): PreparedTemplateDocument {
        val stored = StencilInstances.collapse(document)
        validator.validateTemplateDraft(stored)
        return prepareValidated(stored, catalogKey)
    }

    private fun prepareValidated(document: TemplateDocument, catalogKey: CatalogKey): PreparedTemplateDocument {
        val referencedPaths = pathExtractor.extractReferencedPaths(document)
        // Stored in its released form (ADR 0033): own-catalog references relative, no name
        // qualifiers, so the draft holds the model its release will. Reads qualify them again
        // (TemplateModels.read), and a move restores them in the destination's form. Data-path
        // extraction is unaffected by the catalog and still reads the typed document.
        val model = TemplateModels.stored(document, catalogKey.value, objectMapper)
        return PreparedTemplateDocument(
            templateModelJson = objectMapper.writeValueAsString(model),
            referencedPaths = referencedPaths,
            referencedPathsJson = objectMapper.writeValueAsString(referencedPaths),
        )
    }
}

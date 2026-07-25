// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.services

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
 */
@Component
class TemplateDocumentPreparation(
    private val objectMapper: ObjectMapper,
    private val pathExtractor: TemplatePathExtractor,
    private val validator: TemplateDocumentValidator,
) {
    fun prepare(document: TemplateDocument): PreparedTemplateDocument {
        validator.validateTemplate(document)
        val referencedPaths = pathExtractor.extractReferencedPaths(document)
        return PreparedTemplateDocument(
            templateModelJson = objectMapper.writeValueAsString(document),
            referencedPaths = referencedPaths,
            referencedPathsJson = objectMapper.writeValueAsString(referencedPaths),
        )
    }
}

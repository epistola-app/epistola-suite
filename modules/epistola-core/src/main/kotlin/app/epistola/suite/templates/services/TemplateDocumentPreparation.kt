// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.services

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.graph.ResourceReferenceSites
import app.epistola.suite.catalog.identity.CatalogResourceAliases
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.templates.analysis.TemplatePathExtractor
import app.epistola.suite.templates.validation.TemplateDocumentValidator
import app.epistola.template.model.TemplateDocument
import org.jdbi.v3.core.Jdbi
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
    private val jdbi: Jdbi,
    private val aliases: CatalogResourceAliases,
) {
    fun prepare(document: TemplateDocument, tenantKey: TenantKey, catalogKey: CatalogKey): PreparedTemplateDocument {
        validator.validateTemplate(document)
        return prepareValidated(document, tenantKey, catalogKey)
    }

    /** Prepare an editable draft, allowing required parameter bindings to be incomplete. */
    fun prepareDraft(document: TemplateDocument, tenantKey: TenantKey, catalogKey: CatalogKey): PreparedTemplateDocument {
        validator.validateTemplateDraft(document)
        return prepareValidated(document, tenantKey, catalogKey)
    }

    private fun prepareValidated(document: TemplateDocument, tenantKey: TenantKey, catalogKey: CatalogKey): PreparedTemplateDocument {
        val referencedPaths = pathExtractor.extractReferencedPaths(document)
        // Stored references always name the catalog they resolve against, so relocating the owner
        // cannot silently change what an already-written reference means. Data-path extraction is
        // unaffected by the catalog and still reads the typed document.
        val model = objectMapper.valueToTree<tools.jackson.databind.JsonNode>(document)
        ResourceReferenceSites.qualifyRelative(model, catalogKey.value)
        // A draft reopened from a version published before a relocation still names the old
        // address. The published bytes stay as they are; this copy is mutable, so it is pointed at
        // where the resource actually lives -- otherwise republishing fails validation against an
        // address nothing occupies.
        jdbi.useHandle<Exception> { handle -> aliases.canonicalize(model, aliases.load(handle, tenantKey)) }
        return PreparedTemplateDocument(
            templateModelJson = objectMapper.writeValueAsString(model),
            referencedPaths = referencedPaths,
            referencedPathsJson = objectMapper.writeValueAsString(referencedPaths),
        )
    }
}

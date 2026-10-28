// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates

import app.epistola.catalog.protocol.TemplateResource
import app.epistola.suite.catalog.ProtocolMapper
import app.epistola.suite.catalog.commands.InstallStatus
import app.epistola.suite.catalog.revisions.TemplateContents
import app.epistola.suite.catalog.revisions.VariantRows
import app.epistola.suite.catalog.types.Arrival
import app.epistola.suite.catalog.types.ContentLayout
import app.epistola.suite.catalog.types.ResourceType
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.mediator.execute
import app.epistola.suite.templates.commands.ImportStatus
import app.epistola.suite.templates.commands.ImportTemplateInput
import app.epistola.suite.templates.commands.ImportTemplates
import app.epistola.suite.templates.commands.ImportVariantInput
import app.epistola.suite.templates.model.DataExample
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * A template as a catalog resource type (ADR 0039): stored as its own content and one row per
 * variant with its model ([TemplateContents]), and installed after everything a template can name.
 */
@Component
class TemplateResourceType(
    private val objectMapper: ObjectMapper,
    private val protocolMapper: ProtocolMapper,
) : ResourceType<TemplateResource> {
    override val name = TemplateStore.TEMPLATE
    override val wireClass = TemplateResource::class.java
    override val installedAfter = setOf("theme", "stencil", "attribute", "font", "image")
    override val layout: ContentLayout = object : ContentLayout.Parts {
        override val partType = VariantRows.TYPE

        override fun assemble(parent: JsonNode, parts: Map<String, JsonNode>): JsonNode = TemplateContents.assemble(parent, parts, objectMapper)
    }

    override fun receive(catalog: CatalogId, resource: TemplateResource, arrival: Arrival): InstallStatus {
        val result = ImportTemplates(catalog = catalog, templates = listOf(importInput(resource, arrival))).execute().first()
        if (result.status == ImportStatus.FAILED) {
            throw RuntimeException("Import failed for '${resource.key}': ${result.errorMessage}")
        }
        return if (result.status == ImportStatus.CREATED) InstallStatus.INSTALLED else InstallStatus.UPDATED
    }

    /**
     * A received template as [ImportTemplates] writes it: every member the protocol carries, the wire v8
     * ones (description, metadata, a data example's key) included.
     */
    private fun importInput(resource: TemplateResource, arrival: Arrival): ImportTemplateInput = ImportTemplateInput(
        slug = resource.key,
        name = resource.name,
        version = arrival.version,
        themeId = resource.themeKey,
        themeCatalog = if (resource.themeKey != null) arrival.resolve(resource.themeCatalogKey) else null,
        pdfaEnabled = resource.pdfaEnabled,
        dataModel = protocolMapper.toObjectNode(resource.dataModel),
        dataExamples = resource.dataExamples?.map {
            DataExample(id = java.util.UUID.randomUUID().toString(), name = it.name, data = protocolMapper.toObjectNode(it.data)!!, key = it.key)
        } ?: emptyList(),
        templateModel = resource.templateModel,
        variants = resource.variants.map { variant ->
            ImportVariantInput(
                id = variant.key,
                title = variant.title,
                attributes = variant.attributes ?: emptyMap(),
                templateModel = variant.templateModel,
                isDefault = variant.isDefault,
                description = variant.description,
            )
        },
        description = resource.description,
        metadata = resource.metadata,
    )
}

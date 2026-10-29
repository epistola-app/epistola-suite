// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates

import app.epistola.catalog.protocol.TemplateResource
import app.epistola.suite.catalog.ProtocolMapper
import app.epistola.suite.catalog.commands.InstallStatus
import app.epistola.suite.catalog.releases.ChangeKind
import app.epistola.suite.catalog.revisions.ContentReference
import app.epistola.suite.catalog.revisions.TemplateContents
import app.epistola.suite.catalog.revisions.VariantRows
import app.epistola.suite.catalog.types.Arrival
import app.epistola.suite.catalog.types.ContentLayout
import app.epistola.suite.catalog.types.ResourceChange
import app.epistola.suite.catalog.types.ResourceType
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.templates.commands.ImportStatus
import app.epistola.suite.templates.commands.ImportTemplateInput
import app.epistola.suite.templates.commands.ImportTemplates
import app.epistola.suite.templates.commands.ImportVariantInput
import app.epistola.suite.templates.commands.MarkTemplateReady
import app.epistola.suite.templates.contracts.SchemaCompatibilityChecker
import app.epistola.suite.templates.model.DataExample
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

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

    override fun markReady(catalog: CatalogId, key: String): Boolean {
        MarkTemplateReady(TemplateId(TemplateKey.of(key), catalog)).execute()
        return true
    }

    /**
     * Its data contract: one that rejects input it accepted before is breaking
     * ([SchemaCompatibilityChecker]), one that accepts more additive. A variant removed, or its
     * attributes changed, is breaking (a caller selects by them); a variant added is additive.
     */
    override fun changes(old: TemplateResource, new: TemplateResource): List<ResourceChange> = buildList {
        fun reason(kind: ChangeKind, text: String) = add(ResourceChange(kind, text))
        val oldSchema = old.dataModel?.let { objectMapper.valueToTree<ObjectNode>(it) }
        val newSchema = new.dataModel?.let { objectMapper.valueToTree<ObjectNode>(it) }
        when {
            oldSchema == newSchema -> Unit

            oldSchema == null -> reason(ChangeKind.BREAKING, "a data contract was added, which rejects input accepted before")

            newSchema == null -> reason(ChangeKind.ADDITIVE, "its data contract was removed, so any input is accepted")

            else -> {
                val result = schemas.checkCompatibility(oldSchema, newSchema)
                if (result.compatible) {
                    reason(ChangeKind.ADDITIVE, "its data contract accepts more input")
                } else {
                    result.breakingChanges.forEach { change ->
                        reason(ChangeKind.BREAKING, "data contract: " + change.description + (change.path.takeIf { it.isNotEmpty() && !change.description.contains(it) }?.let { " ($it)" } ?: ""))
                    }
                }
            }
        }
        val oldVariants = old.variants.orEmpty().associateBy { it.key }
        val newVariants = new.variants.orEmpty().associateBy { it.key }
        (oldVariants.keys - newVariants.keys).sorted().forEach { reason(ChangeKind.BREAKING, "variant '$it' removed") }
        (newVariants.keys - oldVariants.keys).sorted().forEach { reason(ChangeKind.ADDITIVE, "variant '$it' added") }
        oldVariants.keys.intersect(newVariants.keys).sorted().forEach { variant ->
            val was = oldVariants.getValue(variant).attributes.orEmpty()
            val now = newVariants.getValue(variant).attributes.orEmpty()
            if (was != now) reason(ChangeKind.BREAKING, "variant '$variant' attributes changed from ${was.describe()} to ${now.describe()}")
        }
    }

    /**
     * Its theme, beside its models, and the attributes its variants select by: a key `catalog.attribute`
     * names that catalog's, a bare one an attribute found by its key alone (as [app.epistola.suite.attributes.validateAttributes]
     * resolves it).
     */
    override fun references(resource: JsonNode): Set<ContentReference> = buildSet {
        resource.text("themeKey")?.let { add(ContentReference(resource.text("themeCatalogKey"), "theme", it)) }
        resource.path("variants").forEach { variant ->
            variant.path("attributes").properties().map { it.key }.forEach { name ->
                val alias = name.substringBefore('.', missingDelimiterValue = "").ifEmpty { null }
                add(ContentReference(alias, "attribute", name.substringAfter('.')))
            }
        }
    }

    /** Its theme, and each variant's attributes, keeping a value under the attribute's new name. */
    override fun repoint(resource: ObjectNode, to: (ContentReference) -> ContentReference?): Boolean {
        var changed = false
        resource.text("themeKey")?.let { key ->
            to(ContentReference(resource.text("themeCatalogKey"), "theme", key))?.let { moved ->
                resource.put("themeKey", moved.key)
                if (moved.alias == null) resource.remove("themeCatalogKey") else resource.put("themeCatalogKey", moved.alias)
                changed = true
            }
        }
        resource.path("variants").forEach { variant ->
            val attributes = variant.get("attributes") as? ObjectNode ?: return@forEach
            val renamed = attributes.properties().map { it.key }.mapNotNull { name ->
                val alias = name.substringBefore('.', missingDelimiterValue = "").ifEmpty { null }
                to(ContentReference(alias, "attribute", name.substringAfter('.')))?.let { moved -> name to (moved.alias?.let { "$it.${moved.key}" } ?: moved.key) }
            }
            if (renamed.isEmpty()) return@forEach
            val values = attributes.properties().associate { it.key to it.value }
            attributes.removeAll()
            values.forEach { (name, value) -> attributes.set(renamed.toMap()[name] ?: name, value) }
            changed = true
        }
        return changed
    }

    private fun JsonNode.text(field: String): String? = get(field)?.takeIf { it.isString }?.asString()?.takeIf { it.isNotBlank() }

    private val schemas = SchemaCompatibilityChecker()

    private fun Map<String, String>.describe() = if (isEmpty()) "none" else entries.sortedBy { it.key }.joinToString(", ") { "${it.key}=${it.value}" }

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
                title = variant.name,
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

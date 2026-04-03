package app.epistola.suite.catalog.protocol

import app.epistola.suite.templates.model.TemplateDocument
import tools.jackson.databind.node.ObjectNode

/**
 * Wire format for a single resource detail JSON fetched from a catalog's detailUrl.
 * Matches the schema defined in docs/exchange.md.
 */
data class ResourceDetail(
    val schemaVersion: Int,
    val resource: TemplateResource,
)

data class TemplateResource(
    val type: String,
    val slug: String,
    val name: String,
    val dataModel: ObjectNode? = null,
    val dataExamples: List<DataExampleEntry>? = null,
    val templateModel: TemplateDocument,
    val variants: List<VariantEntry>,
)

data class DataExampleEntry(
    val name: String,
    val data: ObjectNode,
)

data class VariantEntry(
    val id: String,
    val title: String? = null,
    val attributes: Map<String, String>? = null,
    val templateModel: TemplateDocument? = null,
    val isDefault: Boolean = false,
)

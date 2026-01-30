package app.epistola.suite.templates

import app.epistola.suite.templates.model.DataExample
import app.epistola.suite.templates.model.VariantSummary
import org.jdbi.v3.json.Json
import tools.jackson.databind.node.ObjectNode
import java.time.OffsetDateTime

/**
 * Document template entity.
 * Defines the input contract (schema + examples) and groups related variants.
 * The visual content (templateModel) is now stored in TemplateVersion.
 */
data class DocumentTemplate(
    val id: Long,
    val tenantId: Long,
    val name: String,
    @Json val schema: ObjectNode? = null,
    @Json val dataModel: ObjectNode? = null,
    @Json val dataExamples: List<DataExample> = emptyList(),
    val createdAt: OffsetDateTime,
    val lastModified: OffsetDateTime,
)

/**
 * Template with variant summaries for API responses.
 */
data class DocumentTemplateWithVariants(
    val id: Long,
    val tenantId: Long,
    val name: String,
    @Json val schema: ObjectNode? = null,
    @Json val dataModel: ObjectNode? = null,
    @Json val dataExamples: List<DataExample> = emptyList(),
    val variants: List<VariantSummary>,
    val createdAt: OffsetDateTime,
    val lastModified: OffsetDateTime,
)

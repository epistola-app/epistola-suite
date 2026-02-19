package app.epistola.suite.templates

import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.templates.model.DataExamples
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
    val id: TemplateId,
    val tenantId: TenantId,
    val name: String,
    val themeId: ThemeId? = null,
    @Json val schema: ObjectNode? = null,
    @Json val dataModel: ObjectNode? = null,
    @Json val dataExamples: DataExamples = DataExamples.EMPTY,
    val pdfaEnabled: Boolean = false,
    val createdAt: OffsetDateTime,
    val lastModified: OffsetDateTime,
)

/**
 * Template with variant summaries for API responses.
 */
data class DocumentTemplateWithVariants(
    val id: TemplateId,
    val tenantId: TenantId,
    val name: String,
    val themeId: ThemeId? = null,
    @Json val schema: ObjectNode? = null,
    @Json val dataModel: ObjectNode? = null,
    @Json val dataExamples: DataExamples = DataExamples.EMPTY,
    val pdfaEnabled: Boolean = false,
    val variants: List<VariantSummary>,
    val createdAt: OffsetDateTime,
    val lastModified: OffsetDateTime,
)

package app.epistola.suite.templates

import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeKey
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
    val id: TemplateKey,
    val tenantKey: TenantKey,
    val name: String,
    val themeKey: ThemeKey? = null,
    @Json val schema: ObjectNode? = null,
    @Json val publishedDataModel: ObjectNode? = null,
    @Json val publishedDataExamples: DataExamples = DataExamples.EMPTY,
    @Json val draftDataModel: ObjectNode? = null,
    @Json val draftDataExamples: DataExamples? = null,
    val pdfaEnabled: Boolean = false,
    val createdAt: OffsetDateTime,
    val lastModified: OffsetDateTime,
) {
    val dataModel: ObjectNode? get() = draftDataModel ?: publishedDataModel
    val dataExamples: DataExamples get() = draftDataExamples ?: publishedDataExamples
    val hasDraftContract: Boolean get() = draftDataModel != null || draftDataExamples != null
}

/**
 * Template with variant summaries for API responses.
 */
data class DocumentTemplateWithVariants(
    val id: TemplateKey,
    val tenantKey: TenantKey,
    val name: String,
    val themeKey: ThemeKey? = null,
    @Json val schema: ObjectNode? = null,
    @Json val publishedDataModel: ObjectNode? = null,
    @Json val publishedDataExamples: DataExamples = DataExamples.EMPTY,
    @Json val draftDataModel: ObjectNode? = null,
    @Json val draftDataExamples: DataExamples? = null,
    val pdfaEnabled: Boolean = false,
    val variants: List<VariantSummary>,
    val createdAt: OffsetDateTime,
    val lastModified: OffsetDateTime,
)

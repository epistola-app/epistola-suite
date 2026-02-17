package app.epistola.suite.templates.model

import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import org.jdbi.v3.json.Json
import java.time.OffsetDateTime

/**
 * Template variant representing a presentation variation (language, brand, audience).
 */
data class TemplateVariant(
    val id: VariantId,
    val tenantId: TenantId,
    val templateId: TemplateId,
    val title: String?,
    val description: String?,
    @Json val attributes: Map<String, String> = emptyMap(),
    val isDefault: Boolean,
    val createdAt: OffsetDateTime,
    val lastModified: OffsetDateTime,
)

/**
 * Summary of a variant with version statistics.
 */
data class VariantSummary(
    val id: VariantId,
    val title: String?,
    @Json val attributes: Map<String, String>,
    val isDefault: Boolean,
    val hasDraft: Boolean,
    @Json val publishedVersions: List<Int>,
)

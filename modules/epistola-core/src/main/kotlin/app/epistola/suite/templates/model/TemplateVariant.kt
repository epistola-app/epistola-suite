package app.epistola.suite.templates.model

import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantKey
import org.jdbi.v3.json.Json
import java.time.OffsetDateTime

/**
 * Template variant representing a presentation variation (language, brand, audience).
 */
data class TemplateVariant(
    val id: VariantKey,
    val tenantId: TenantKey,
    val templateId: TemplateKey,
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
    val id: VariantKey,
    val title: String?,
    @Json val attributes: Map<String, String>,
    val isDefault: Boolean,
    val hasDraft: Boolean,
    @Json val publishedVersions: List<Int>,
)

package app.epistola.suite.templates.model

import org.jdbi.v3.json.Json
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Template variant representing a presentation variation (language, brand, audience).
 */
data class TemplateVariant(
    val id: UUID,
    val templateId: UUID,
    val title: String?,
    val description: String?,
    @Json val tags: Map<String, String> = emptyMap(),
    val createdAt: OffsetDateTime,
    val lastModified: OffsetDateTime,
)

/**
 * Summary of a variant with version statistics.
 */
data class VariantSummary(
    val id: UUID,
    val title: String?,
    @Json val tags: Map<String, String>,
    val hasDraft: Boolean,
    @Json val publishedVersions: List<Int>,
)

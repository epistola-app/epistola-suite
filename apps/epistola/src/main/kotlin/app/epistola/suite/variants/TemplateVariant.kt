package app.epistola.suite.variants

import org.jdbi.v3.json.Json
import java.time.OffsetDateTime

/**
 * Template variant representing a presentation variation (language, brand, audience).
 */
data class TemplateVariant(
    val id: Long,
    val templateId: Long,
    @Json val tags: Map<String, String> = emptyMap(),
    val createdAt: OffsetDateTime,
    val lastModified: OffsetDateTime,
)

/**
 * Summary of a variant with version statistics.
 */
data class VariantSummary(
    val id: Long,
    @Json val tags: Map<String, String>,
    val hasDraft: Boolean,
    val publishedVersionCount: Int,
    val latestPublishedVersion: Int?,
)

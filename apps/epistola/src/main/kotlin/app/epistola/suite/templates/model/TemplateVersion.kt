package app.epistola.suite.templates.model

import app.epistola.template.model.TemplateModel
import org.jdbi.v3.json.Json
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Lifecycle status of a template version.
 */
enum class VersionStatus {
    DRAFT,
    PUBLISHED,
    ARCHIVED,
}

/**
 * Template version containing the actual content with lifecycle state.
 */
data class TemplateVersion(
    val id: UUID,
    val variantId: UUID,
    val versionNumber: Int?,
    @Json val templateModel: TemplateModel?,
    val status: VersionStatus,
    val createdAt: OffsetDateTime,
    val publishedAt: OffsetDateTime?,
    val archivedAt: OffsetDateTime?,
)

/**
 * Summary view of a version for list displays.
 */
data class VersionSummary(
    val id: UUID,
    val variantId: UUID,
    val versionNumber: Int?,
    val status: VersionStatus,
    val createdAt: OffsetDateTime,
    val publishedAt: OffsetDateTime?,
    val archivedAt: OffsetDateTime?,
)

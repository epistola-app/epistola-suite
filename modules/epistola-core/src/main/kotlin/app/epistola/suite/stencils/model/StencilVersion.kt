package app.epistola.suite.stencils.model

import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VersionKey
import app.epistola.template.model.TemplateDocument
import org.jdbi.v3.json.Json
import java.time.OffsetDateTime

/**
 * Lifecycle status of a stencil version.
 */
enum class StencilVersionStatus {
    DRAFT,
    PUBLISHED,
    ARCHIVED,
}

/**
 * Stencil version containing the actual content with lifecycle state.
 * The id field IS the version number (1-200).
 */
data class StencilVersion(
    val id: VersionKey,
    val tenantKey: TenantKey,
    val stencilKey: StencilKey,
    @Json val content: TemplateDocument,
    val status: StencilVersionStatus,
    val createdAt: OffsetDateTime,
    val publishedAt: OffsetDateTime?,
    val archivedAt: OffsetDateTime?,
)

/**
 * Summary view of a stencil version for list displays.
 */
data class StencilVersionSummary(
    val id: VersionKey,
    val status: StencilVersionStatus,
    val createdAt: OffsetDateTime,
    val publishedAt: OffsetDateTime?,
    val archivedAt: OffsetDateTime?,
)

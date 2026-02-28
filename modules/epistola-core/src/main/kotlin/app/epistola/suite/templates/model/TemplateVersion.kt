package app.epistola.suite.templates.model

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.themes.ResolvedThemeSnapshot
import app.epistola.template.model.TemplateDocument
import org.jdbi.v3.json.Json
import java.time.OffsetDateTime

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
 * The id field IS the version number (1-200) - no separate versionNumber field.
 */
data class TemplateVersion(
    val id: VersionKey,
    val tenantKey: TenantKey,
    val variantKey: VariantKey,
    @Json val templateModel: TemplateDocument,
    val status: VersionStatus,
    val createdAt: OffsetDateTime,
    val publishedAt: OffsetDateTime?,
    val archivedAt: OffsetDateTime?,
    /** Version of RenderingDefaults frozen at publish time. Null for drafts and legacy published versions. */
    val renderingDefaultsVersion: Int? = null,
    /** Theme snapshot frozen at publish time. Null for drafts and legacy published versions. */
    @Json val resolvedTheme: ResolvedThemeSnapshot? = null,
)

/**
 * Summary view of a version for list displays.
 * The id field IS the version number (1-200) - no separate versionNumber field.
 */
data class VersionSummary(
    val id: VersionKey,
    val tenantKey: TenantKey,
    val variantKey: VariantKey,
    val status: VersionStatus,
    val createdAt: OffsetDateTime,
    val publishedAt: OffsetDateTime?,
    val archivedAt: OffsetDateTime?,
)

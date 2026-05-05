package app.epistola.suite.mcp.dto

import app.epistola.suite.templates.model.VersionSummary
import java.time.OffsetDateTime

/**
 * Summary of a template version — drafts and published versions for a variant.
 *
 * A variant has at most one draft (status = "draft") plus zero or more published
 * versions. Drafts are the editable working copy; published versions are immutable.
 */
data class VersionInfo(
    /** Sequential version number (1..N), per variant. */
    val id: Int,
    val variantId: String,
    /** "draft", "published", or "archived". */
    val status: String,
    val createdAt: OffsetDateTime,
    val publishedAt: OffsetDateTime?,
    val archivedAt: OffsetDateTime?,
    /** Contract version this template version was published against (if any). */
    val contractVersion: Int?,
) {
    companion object {
        fun from(version: VersionSummary): VersionInfo = VersionInfo(
            id = version.id.value,
            variantId = version.variantKey.value,
            status = version.status.name.lowercase(),
            createdAt = version.createdAt,
            publishedAt = version.publishedAt,
            archivedAt = version.archivedAt,
            contractVersion = version.contractVersion?.value,
        )
    }
}

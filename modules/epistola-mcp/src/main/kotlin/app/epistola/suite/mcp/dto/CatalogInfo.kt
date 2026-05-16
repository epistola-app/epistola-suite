package app.epistola.suite.mcp.dto

import app.epistola.suite.catalog.Catalog
import java.time.OffsetDateTime

/**
 * Catalog within the current tenant. Templates, themes, and stencils are
 * scoped to a catalog; AI tools that operate on those resources need a
 * `catalogId` argument and discover available catalogs through `list_catalogs`.
 */
data class CatalogInfo(
    /** Catalog key (slug). Used as `catalogId` arg to other tools. */
    val id: String,
    val name: String,
    val description: String?,
    /** AUTHORED (editable in this tenant) or SUBSCRIBED (read-only mirror of remote source). */
    val type: String,
    val sourceUrl: String?,
    val installedReleaseVersion: String?,
    val installedAt: OffsetDateTime?,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
) {
    companion object {
        fun from(catalog: Catalog): CatalogInfo = CatalogInfo(
            id = catalog.id.value,
            name = catalog.name,
            description = catalog.description,
            type = catalog.type.name,
            sourceUrl = catalog.sourceUrl,
            installedReleaseVersion = catalog.installedReleaseVersion,
            installedAt = catalog.installedAt,
            createdAt = catalog.createdAt,
            updatedAt = catalog.updatedAt,
        )
    }
}

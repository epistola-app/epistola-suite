package app.epistola.suite.stencils

import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.stencils.model.StencilVersionSummary
import org.jdbi.v3.json.Json
import java.time.OffsetDateTime

/**
 * Stencil entity — a reusable template component.
 */
data class Stencil(
    val id: StencilKey,
    val tenantKey: TenantKey,
    val catalogKey: CatalogKey = CatalogKey.DEFAULT,
    val catalogType: CatalogType = CatalogType.AUTHORED,
    val name: String,
    val description: String? = null,
    @Json val tags: List<String> = emptyList(),
    val createdAt: OffsetDateTime,
    val lastModified: OffsetDateTime,
)

/**
 * Stencil summary with version metadata, computed in a single query.
 * Used by list endpoints to avoid N+1 queries.
 */
data class StencilSummaryWithVersionInfo(
    val id: StencilKey,
    val tenantKey: TenantKey,
    val catalogKey: CatalogKey = CatalogKey.DEFAULT,
    val name: String,
    val description: String? = null,
    @Json val tags: List<String> = emptyList(),
    val latestPublishedVersion: Int? = null,
    val latestVersion: Int? = null,
    val createdAt: OffsetDateTime,
    val lastModified: OffsetDateTime,
)

/**
 * Stencil with version summaries for API responses.
 */
data class StencilWithVersions(
    val id: StencilKey,
    val tenantKey: TenantKey,
    val catalogKey: CatalogKey = CatalogKey.DEFAULT,
    val name: String,
    val description: String? = null,
    @Json val tags: List<String> = emptyList(),
    val versions: List<StencilVersionSummary>,
    val createdAt: OffsetDateTime,
    val lastModified: OffsetDateTime,
)

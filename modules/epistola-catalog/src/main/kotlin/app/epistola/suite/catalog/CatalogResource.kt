package app.epistola.suite.catalog

import app.epistola.suite.common.ids.TenantKey

/**
 * Tracks a resource installed from a catalog. Generic for all resource types
 * (templates, themes, stencils, attributes, assets).
 */
data class CatalogResource(
    val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val resourceType: String,
    val resourceSlug: String,
)

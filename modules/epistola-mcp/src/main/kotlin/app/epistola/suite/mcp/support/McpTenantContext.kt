// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.support

import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.query
import app.epistola.suite.security.SecurityContext

/**
 * Resolve the tenant the current MCP request is operating in.
 *
 * MCP tools authenticate via per-tenant `Authorization: ApiKey` credentials;
 * the API-key filter populates [app.epistola.suite.security.EpistolaPrincipal.currentTenantId].
 * Tools therefore never take a `tenantId` parameter — it is implicit in
 * the auth context.
 */
internal fun mcpTenantKey(): TenantKey = SecurityContext.current().currentTenantId
    ?: error("MCP request has no tenant scope: API key is not bound to a tenant")

internal fun mcpTenantId(): TenantId = TenantId(mcpTenantKey())

/**
 * The catalog a list tool filters by, or null for "all catalogs". A catalog that does not exist
 * fails rather than filtering to nothing: an empty list would read as "this catalog is empty".
 */
internal fun catalogFilter(catalogId: String?): CatalogKey? {
    val key = catalogId?.takeIf { it.isNotBlank() }?.let { CatalogKey.of(it) } ?: return null
    GetCatalog(mcpTenantKey(), key).query()
        ?: throw NoSuchElementException("Catalog '$catalogId' not found. Use `list_catalogs` to see the catalogs in this tenant.")
    return key
}

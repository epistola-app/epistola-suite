// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.support

import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.versions.CatalogVersionRef
import app.epistola.suite.catalog.versions.DraftRef
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.query
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.validation.ValidationException

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

/** The description of the optional `catalogVersion` argument of every tool that addresses catalog content. */
internal const val CATALOG_VERSION_DESCRIPTION = "Draft of the catalog to work in: `next`, or parallel work by its " +
    "`draftId` from `list_catalog_versions`. Omit for the catalog's current version: its next version (a SUBSCRIBED " +
    "catalog's installed release)."

/**
 * The draft a tool's `catalogVersion` argument names, of catalog [catalogId], or null for its current
 * version. A version belongs to one catalog, so naming one without a catalog is refused.
 */
internal fun catalogVersionRef(catalogId: String?, catalogVersion: String?): CatalogVersionRef? {
    val version = catalogVersion?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val catalog = catalogId?.takeIf { it.isNotBlank() }
        ?: throw ValidationException("catalogVersion", "`catalogVersion` is a version of one catalog; give `catalogId` too")
    val draft = DraftRef.parse(version)
        ?: throw ValidationException("catalogVersion", "`catalogVersion` is `next` or a `draftId` from `list_catalog_versions`; a draft has no number until it is released")
    return CatalogVersionRef(mcpTenantKey(), CatalogKey.of(catalog), draft)
}

/**
 * The description of every update tool's `expectedRevision`: the optimistic lock
 * ([app.epistola.suite.common.Revisions]).
 */
internal const val EXPECTED_REVISION_DESCRIPTION =
    "The `revision` the content had when you read it (from the matching get tool). When given, the update is " +
        "refused if someone changed it since, and nothing is written: read it again and reapply your change. " +
        "Omit to overwrite whatever is there."

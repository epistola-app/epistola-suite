// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.support

import app.epistola.suite.catalog.queries.CatalogEditabilities
import app.epistola.suite.catalog.queries.FindCatalogsByKey
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.ListCatalogEditability
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.DraftRef
import app.epistola.suite.common.ids.CatalogId
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
 * The catalog a tool's `catalogId` names, by its address (ADR 0034, section 9): `namespace/key`, or its
 * key alone while the tenant has one catalog with that key. A key that names none is not found; one
 * that names several is refused, naming them.
 */
internal fun mcpCatalog(catalogId: String): CatalogId {
    val value = catalogId.trim()
    val namespace = value.substringBeforeLast('/', "")
    val key = CatalogKey.of(value.substringAfterLast('/'))
    if (namespace.isNotEmpty()) return CatalogId(namespace, key, mcpTenantId())
    val found = FindCatalogsByKey(mcpTenantKey(), key).query().catalogs
    return found.singleOrNull() ?: if (found.isEmpty()) {
        throw NoSuchElementException("Catalog '$value' not found. Use `list_catalogs` to see the catalogs in this tenant.")
    } else {
        throw ValidationException("catalogId", "Two catalogs have the key '$value': name one as ${found.joinToString(" or ") { "`${it.address}`" }}.")
    }
}

/** The description of a tool's `catalogId`: its key, or `namespace/key`. */
internal const val CATALOG_ID_NAMESPACE_HINT = "Write it `namespace/key` (from `list_catalogs`) when two catalogs share the key; the key alone names the one catalog with it."

/**
 * The catalog a list tool filters by, or null for "all catalogs". A catalog that does not exist
 * fails rather than filtering to nothing: an empty list would read as "this catalog is empty".
 */
internal fun catalogFilter(catalogId: String?): CatalogId? {
    val catalog = catalogId?.takeIf { it.isNotBlank() }?.let { mcpCatalog(it) } ?: return null
    GetCatalog(catalog).query()
        ?: throw NoSuchElementException("Catalog '$catalogId' not found. Use `list_catalogs` to see the catalogs in this tenant.")
    return catalog
}

/**
 * Whether each of the tenant's catalogs can be changed here (ADR 0032), read once for a tool that
 * returns resources of several catalogs.
 */
internal fun catalogEditability(): CatalogEditabilities = ListCatalogEditability(mcpTenantKey()).query()

/** The description of the optional `catalogVersion` argument of every tool that addresses catalog content. */
internal const val CATALOG_VERSION_DESCRIPTION = "Draft of the catalog to work in: `next`, or parallel work by its " +
    "`draftId` from `list_catalog_releases`. Omit for the next release, where everyday work goes."

/**
 * The draft a tool's `catalogVersion` argument names, of catalog [catalogId], or null for its current
 * version. A version belongs to one catalog, so naming one without a catalog is refused.
 */
internal fun catalogVersionRef(catalogId: String?, catalogVersion: String?): CatalogReleaseRef? {
    val version = catalogVersion?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val catalog = catalogId?.takeIf { it.isNotBlank() }
        ?: throw ValidationException("catalogVersion", "`catalogVersion` is a draft or release of one catalog; give `catalogId` too")
    val draft = DraftRef.parseDraft(version)
        ?: throw ValidationException("catalogVersion", "`catalogVersion` is `next` or a `draftId` from `list_catalog_releases`; a draft has no number until it is released")
    return CatalogReleaseRef(mcpCatalog(catalog), draft)
}

/**
 * The description of every update tool's `expectedRevision`: the optimistic lock
 * ([app.epistola.suite.common.Revisions]).
 */
internal const val EXPECTED_REVISION_DESCRIPTION =
    "The `revision` the content had when you read it (from the matching get tool). When given, the update is " +
        "refused if someone changed it since, and nothing is written: read it again and reapply your change. " +
        "Omit to overwrite whatever is there."

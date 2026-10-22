// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.support

import app.epistola.suite.catalog.CatalogNames
import app.epistola.suite.catalog.queries.CatalogEditabilities
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.ListCatalogEditability
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.DraftRef
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
 * The catalog a tool's `catalogId` names: its key, or `namespace/key` when the key alone does not
 * say which (ADR 0034, phase 4: within a tenant a catalog is unique by namespace and key). The
 * namespace holds for the rest of the tool call, so the key means that catalog in everything the tool
 * does. A key that names several catalogs without one is refused, naming them.
 */
internal fun mcpCatalogKey(catalogId: String): CatalogKey {
    val value = catalogId.trim()
    val namespace = value.substringBeforeLast('/', "")
    val key = CatalogKey.of(value.substringAfterLast('/'))
    if (namespace.isNotEmpty() && !CatalogNames.name(key, namespace)) {
        throw ValidationException("catalogId", "A catalog named `namespace/key` is not taken outside an MCP request.")
    }
    return key
}

/** The description of a tool's `catalogId`: its key, or `namespace/key`. */
internal const val CATALOG_ID_NAMESPACE_HINT = "Write it `namespace/key` (from `list_catalogs`) when two catalogs share the key."

/**
 * The catalog a list tool filters by, or null for "all catalogs". A catalog that does not exist
 * fails rather than filtering to nothing: an empty list would read as "this catalog is empty".
 */
internal fun catalogFilter(catalogId: String?): CatalogKey? {
    val key = catalogId?.takeIf { it.isNotBlank() }?.let { mcpCatalogKey(it) } ?: return null
    GetCatalog(mcpTenantKey(), key).query()
        ?: throw NoSuchElementException("Catalog '$catalogId' not found. Use `list_catalogs` to see the catalogs in this tenant.")
    return key
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
    val draft = DraftRef.parse(version)
        ?: throw ValidationException("catalogVersion", "`catalogVersion` is `next` or a `draftId` from `list_catalog_releases`; a draft has no number until it is released")
    return CatalogReleaseRef(mcpTenantKey(), mcpCatalogKey(catalog), draft)
}

/**
 * The description of every update tool's `expectedRevision`: the optimistic lock
 * ([app.epistola.suite.common.Revisions]).
 */
internal const val EXPECTED_REVISION_DESCRIPTION =
    "The `revision` the content had when you read it (from the matching get tool). When given, the update is " +
        "refused if someone changed it since, and nothing is written: read it again and reapply your change. " +
        "Omit to overwrite whatever is there."

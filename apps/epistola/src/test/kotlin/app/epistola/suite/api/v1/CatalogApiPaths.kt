// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.releases.CatalogReleaseStatus
import app.epistola.suite.catalog.releases.DraftRef
import app.epistola.suite.catalog.releases.ListCatalogReleases
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.testCatalog

/**
 * Paths of the contract 2.0 catalog-scoped REST API, which addresses a catalog by its full name
 * (`/catalogs/{registry}/{namespace}/{catalogKey}`) and its content by version
 * (`…/releases/{release}`). The registry is always `~`, the installation's default; the
 * namespace is read from the catalog rather than assumed.
 *
 * These read the catalog through the mediator, so call them inside `withMediator { }`.
 */
internal object CatalogApiPaths {

    /** `/api/tenants/{tenantKey}/catalogs/~/{namespace}/{catalogKey}`. */
    fun catalog(tenantKey: TenantKey, catalogKey: String = CatalogKey.DEFAULT.value): String {
        val catalog = GetCatalog(testCatalog(tenantKey, CatalogKey.of(catalogKey))).query()
            ?: error("Tenant ${tenantKey.value} has no catalog $catalogKey")
        return "/api/tenants/${tenantKey.value}/catalogs/~/${catalog.namespace}/$catalogKey"
    }

    /**
     * `…/catalogs/~/{namespace}/{catalogKey}/releases/{release}`; without [version], the catalog's
     * next version (`next`), or its highest release when it has none (a subscribed catalog).
     */
    fun version(tenantKey: TenantKey, catalogKey: String = CatalogKey.DEFAULT.value, version: String? = null): String = "${catalog(tenantKey, catalogKey)}/releases/${version ?: currentVersion(tenantKey, catalogKey)}"

    /** How an address names the catalog's current version: `next`, or its highest release when it has no next version. */
    fun currentVersion(tenantKey: TenantKey, catalogKey: String = CatalogKey.DEFAULT.value): String {
        val versions = ListCatalogReleases(testCatalog(tenantKey, CatalogKey.of(catalogKey))).query()
        if (versions.any { it.isNext }) return DraftRef.NEXT
        return versions.firstOrNull { it.status == CatalogReleaseStatus.RELEASED }?.version
            ?: error("Catalog $catalogKey of tenant ${tenantKey.value} has no version: an authored catalog should always have its next version")
    }

    /** The catalog's namespace, as a generation or preview request names the catalog. */
    fun namespace(tenantKey: TenantKey, catalogKey: String = CatalogKey.DEFAULT.value): String = GetCatalog(testCatalog(tenantKey, CatalogKey.of(catalogKey))).query()?.namespace
        ?: error("Tenant ${tenantKey.value} has no catalog $catalogKey")
}

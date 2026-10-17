// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.versions.CatalogVersionStatus
import app.epistola.suite.catalog.versions.ListCatalogVersions
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.query

/**
 * Paths of the contract 2.0 catalog-scoped REST API, which addresses a catalog by its full name
 * (`/catalogs/{registry}/{namespace}/{catalogKey}`) and its content by version
 * (`…/versions/{catalogVersion}`). The registry is always `~`, the installation's default; the
 * namespace is read from the catalog rather than assumed.
 *
 * These read the catalog through the mediator, so call them inside `withMediator { }`.
 */
internal object CatalogApiPaths {

    /** `/api/tenants/{tenantKey}/catalogs/~/{namespace}/{catalogKey}`. */
    fun catalog(tenantKey: TenantKey, catalogKey: String = CatalogKey.DEFAULT.value): String {
        val catalog = GetCatalog(tenantKey, CatalogKey.of(catalogKey)).query()
            ?: error("Tenant ${tenantKey.value} has no catalog $catalogKey")
        return "/api/tenants/${tenantKey.value}/catalogs/~/${catalog.namespace}/$catalogKey"
    }

    /**
     * `…/catalogs/~/{namespace}/{catalogKey}/versions/{version}`; without [version], the catalog's
     * open draft, or its highest release when it has no draft (a subscribed catalog).
     */
    fun version(tenantKey: TenantKey, catalogKey: String = CatalogKey.DEFAULT.value, version: String? = null): String = "${catalog(tenantKey, catalogKey)}/versions/${version ?: currentVersion(tenantKey, catalogKey)}"

    /** The catalog's open draft version, or its highest release when it has no draft. */
    fun currentVersion(tenantKey: TenantKey, catalogKey: String = CatalogKey.DEFAULT.value): String {
        val versions = ListCatalogVersions(tenantKey, CatalogKey.of(catalogKey)).query()
        return (versions.firstOrNull { it.status == CatalogVersionStatus.DRAFT } ?: versions.firstOrNull())?.version
            ?: error("Catalog $catalogKey of tenant ${tenantKey.value} has no version: an authored catalog should always have its draft")
    }

    /** The catalog's namespace, as a generation or preview request names the catalog. */
    fun namespace(tenantKey: TenantKey, catalogKey: String = CatalogKey.DEFAULT.value): String = GetCatalog(tenantKey, CatalogKey.of(catalogKey)).query()?.namespace
        ?: error("Tenant ${tenantKey.value} has no catalog $catalogKey")
}

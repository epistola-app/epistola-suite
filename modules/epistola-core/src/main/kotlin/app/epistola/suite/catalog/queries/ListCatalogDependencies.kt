// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.queries

import app.epistola.suite.catalog.dependencies.CatalogDependencyDeclaration
import app.epistola.suite.catalog.dependencies.CatalogDependencyStore
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/** The catalogs [catalogKey] declares it depends on, in declaration order, each with its lock. */
data class ListCatalogDependencies(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogReleaseRef? = null,
) : CatalogReleaseScoped,
    Query<List<CatalogDependencyDeclaration>>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

@Component
class ListCatalogDependenciesHandler(
    private val jdbi: Jdbi,
    private val store: CatalogDependencyStore,
) : QueryHandler<ListCatalogDependencies, List<CatalogDependencyDeclaration>> {

    override fun handle(query: ListCatalogDependencies): List<CatalogDependencyDeclaration> = jdbi.withHandle<List<CatalogDependencyDeclaration>, Exception> { handle ->
        store.declarations(handle, query.tenantKey, query.catalogKey)
    }
}

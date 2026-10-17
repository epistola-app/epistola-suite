// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.queries

import app.epistola.suite.catalog.dependencies.CatalogDependencyDeclaration
import app.epistola.suite.catalog.dependencies.CatalogDependencyStore
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * The dependency declarations release [version] of [catalogKey] was cut with, each pinned to the
 * release of the dependency it renders with. Empty for a release that declared none.
 */
data class ListReleaseDependencies(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val version: String,
) : Query<List<CatalogDependencyDeclaration>>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

@Component
class ListReleaseDependenciesHandler(
    private val jdbi: Jdbi,
    private val store: CatalogDependencyStore,
) : QueryHandler<ListReleaseDependencies, List<CatalogDependencyDeclaration>> {

    override fun handle(query: ListReleaseDependencies): List<CatalogDependencyDeclaration> = jdbi.withHandle<List<CatalogDependencyDeclaration>, Exception> { handle ->
        store.releasePins(handle, query.tenantKey, query.catalogKey, query.version)
    }
}

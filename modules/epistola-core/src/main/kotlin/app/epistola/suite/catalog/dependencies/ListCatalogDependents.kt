// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.dependencies

import app.epistola.suite.catalog.findCatalog
import app.epistola.suite.catalog.queries.ListCatalogs
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * The catalogs of the tenant whose current version declares a dependency on [catalogKey]: the other
 * side of a catalog's dependencies, so changing a shared catalog says who it affects. By name.
 */
data class ListCatalogDependents(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
) : Query<List<CatalogDependent>>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

/** A catalog that depends on another, and the [lockedTo] release of it that it pins. */
data class CatalogDependent(val catalogKey: String, val name: String, val lockedTo: String?)

@Component
class ListCatalogDependentsHandler(
    private val jdbi: Jdbi,
    private val store: CatalogDependencyStore,
) : QueryHandler<ListCatalogDependents, List<CatalogDependent>> {

    override fun handle(query: ListCatalogDependents): List<CatalogDependent> {
        val others = ListCatalogs(query.tenantKey).query().filter { it.id != query.catalogKey }
        return jdbi.withHandle<List<CatalogDependent>, Exception> { handle ->
            val target = handle.findCatalog(query.tenantKey, query.catalogKey) ?: return@withHandle emptyList()
            val identity = Triple(target.registry, target.namespace, query.catalogKey.value)
            others.mapNotNull { other ->
                store.declarations(handle, query.tenantKey, other.id).firstOrNull { it.identity == identity }
                    ?.let { CatalogDependent(other.id.value, other.name, it.releaseVersion) }
            }.sortedBy { it.name.lowercase() }
        }
    }
}

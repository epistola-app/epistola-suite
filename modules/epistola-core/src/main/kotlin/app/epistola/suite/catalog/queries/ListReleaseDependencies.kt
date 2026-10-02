// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.queries

import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/** A release of another catalog that a release renders with. */
data class ReleaseDependency(
    val catalogKey: CatalogKey,
    val version: String,
    /** True when the release's own resources reference the catalog; false when reached only through another dependency. */
    val direct: Boolean,
)

/**
 * The releases of other catalogs a release renders with, as recorded when it was cut: the whole
 * closure, ordered by catalog.
 */
data class ListReleaseDependencies(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val version: String,
) : Query<List<ReleaseDependency>>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

@Component
class ListReleaseDependenciesHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListReleaseDependencies, List<ReleaseDependency>> {
    override fun handle(query: ListReleaseDependencies): List<ReleaseDependency> = jdbi.withHandle<List<ReleaseDependency>, Exception> { handle ->
        handle.createQuery(
            """
            SELECT dependency_catalog_key, dependency_version, direct FROM release_dependencies
            WHERE tenant_key = :t AND catalog_key = :c AND version = :v
            ORDER BY dependency_catalog_key
            """,
        )
            .bind("t", query.tenantKey)
            .bind("c", query.catalogKey)
            .bind("v", query.version)
            .map { rs, _ ->
                ReleaseDependency(
                    catalogKey = CatalogKey.of(rs.getString("dependency_catalog_key")),
                    version = rs.getString("dependency_version"),
                    direct = rs.getBoolean("direct"),
                )
            }
            .list()
    }
}

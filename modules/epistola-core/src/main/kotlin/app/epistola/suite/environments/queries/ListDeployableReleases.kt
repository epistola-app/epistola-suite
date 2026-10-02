// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.environments.queries

import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/** A release that can be deployed, with the name of its catalog for display. */
data class DeployableRelease(
    val catalogKey: CatalogKey,
    val catalogName: String,
    val version: String,
)

/**
 * Every release of the tenant's catalogs that can be deployed: the ones that kept their content, the
 * same predicate [app.epistola.suite.environments.commands.DeployRelease] enforces. Ordered by catalog
 * name, then newest release first, in the database.
 */
data class ListDeployableReleases(
    override val tenantKey: TenantKey,
) : Query<List<DeployableRelease>>,
    RequiresPermission {
    override val permission get() = Permission.TEMPLATE_VIEW
}

@Component
class ListDeployableReleasesHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListDeployableReleases, List<DeployableRelease>> {
    override fun handle(query: ListDeployableReleases): List<DeployableRelease> = jdbi.withHandle<List<DeployableRelease>, Exception> { handle ->
        handle.createQuery(
            """
            SELECT r.catalog_key, c.name AS catalog_name, r.version
            FROM catalog_releases r
            JOIN catalogs c ON c.tenant_key = r.tenant_key AND c.id = r.catalog_key
            WHERE r.tenant_key = :t AND r.content_retained
            ORDER BY c.name, r.catalog_key,
                     r.version_major DESC NULLS LAST, r.version_minor DESC NULLS LAST,
                     r.version_patch DESC NULLS LAST, r.released_at DESC
            """,
        )
            .bind("t", query.tenantKey)
            .map { rs, _ ->
                DeployableRelease(
                    catalogKey = CatalogKey.of(rs.getString("catalog_key")),
                    catalogName = rs.getString("catalog_name"),
                    version = rs.getString("version"),
                )
            }
            .list()
    }
}

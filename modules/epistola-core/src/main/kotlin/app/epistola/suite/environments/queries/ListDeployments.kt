// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.environments.queries

import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.environments.CatalogDeployment
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

/**
 * The releases a tenant's environments serve, optionally for one catalog or one environment. Ordered
 * by environment, then catalog, in the database.
 */
data class ListDeployments(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey? = null,
    val environmentKey: EnvironmentKey? = null,
) : Query<List<CatalogDeployment>>,
    RequiresPermission {
    override val permission get() = Permission.TEMPLATE_VIEW
}

@Component
class ListDeploymentsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListDeployments, List<CatalogDeployment>> {
    override fun handle(query: ListDeployments): List<CatalogDeployment> = jdbi.withHandle<List<CatalogDeployment>, Exception> { handle ->
        handle.createQuery(
            """
            SELECT environment_key, catalog_key, version, deployed_at, deployed_by
            FROM environment_catalog_deployments
            WHERE tenant_key = :t
              AND (CAST(:c AS TEXT) IS NULL OR catalog_key = :c)
              AND (CAST(:e AS TEXT) IS NULL OR environment_key = :e)
            ORDER BY environment_key, catalog_key
            """,
        )
            .bind("t", query.tenantKey)
            .bind("c", query.catalogKey)
            .bind("e", query.environmentKey)
            .mapTo<CatalogDeployment>()
            .list()
    }
}

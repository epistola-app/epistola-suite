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
 * The releases a tenant's environments serve, optionally for one catalog or one environment. An
 * environment may serve several releases of one catalog. Ordered by environment, then catalog, then
 * highest release first (the order generation picks in), in the database.
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
            SELECT d.environment_key, cat.catalog_key AS catalog_key, d.version, d.deployed_at, d.deployed_by, d.documents_requested, d.last_used_at
            FROM environment_catalog_deployments d
            JOIN catalogs cat ON cat.tenant_key = d.tenant_key AND cat.catalog_id = d.catalog_id
            JOIN catalog_releases r ON r.tenant_key = d.tenant_key AND r.catalog_id = d.catalog_id AND r.version = d.version
            WHERE d.tenant_key = :t
              AND (CAST(:c AS TEXT) IS NULL OR cat.catalog_key = :c)
              AND (CAST(:e AS TEXT) IS NULL OR d.environment_key = :e)
            ORDER BY d.environment_key, cat.catalog_key,
                     r.version_major DESC NULLS LAST, r.version_minor DESC NULLS LAST,
                     r.version_patch DESC NULLS LAST, r.released_at DESC
            """,
        )
            .bind("t", query.tenantKey)
            .bind("c", query.catalogKey)
            .bind("e", query.environmentKey)
            .mapTo<CatalogDeployment>()
            .list()
    }
}

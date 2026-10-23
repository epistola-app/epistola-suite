// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.environments.queries

import app.epistola.suite.catalog.CatalogAddress
import app.epistola.suite.catalog.CatalogNamesApplier
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.generation.release.CatalogSelection
import app.epistola.suite.generation.release.ReleaseTargetResolver
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * What a request through [environmentKey] that names no version gets, per catalog the environment
 * serves: the draft it serves, else the highest release it serves. Generation decides the same
 * way ([ReleaseTargetResolver.select]); this is that answer, for showing it.
 */
data class GetEnvironmentLatest(
    override val tenantKey: TenantKey,
    val environmentKey: EnvironmentKey,
) : Query<Map<CatalogAddress, CatalogSelection>>,
    RequiresPermission {
    override val permission get() = Permission.TEMPLATE_VIEW
}

@Component
class GetEnvironmentLatestHandler(
    private val jdbi: Jdbi,
    private val resolver: ReleaseTargetResolver,
    private val catalogNames: CatalogNamesApplier,
) : QueryHandler<GetEnvironmentLatest, Map<CatalogAddress, CatalogSelection>> {
    override fun handle(query: GetEnvironmentLatest): Map<CatalogAddress, CatalogSelection> {
        val catalogs = jdbi.withHandle<List<CatalogAddress>, Exception> { handle ->
            handle.createQuery(
                """
                SELECT DISTINCT c.namespace, c.catalog_key FROM environment_catalog_deployments d
                JOIN catalogs c ON c.tenant_key = d.tenant_key AND c.catalog_id = d.catalog_id
                WHERE d.tenant_key = :t AND d.environment_key = :e
                """,
            )
                .bind("t", query.tenantKey).bind("e", query.environmentKey)
                .map { rs, _ -> CatalogAddress(rs.getString("namespace"), CatalogKey.of(rs.getString("catalog_key"))) }
                .list()
        }
        // Each catalog in its namespace: two can share a key (ADR 0034).
        return catalogs.associateWith { catalogNames.named(it.key, it.namespace) { resolver.select(query.tenantKey, it.key, query.environmentKey, null) } }
    }
}

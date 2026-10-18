// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.environments.queries

import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * What keeps a tenant's documents from being made, as the tenant home tells it.
 *
 * @property idleEnvironments environments that serve no catalog and follow no draft, by name: every
 *   request naming one fails. A new environment starts so, and after an upgrade from 1.x every
 *   environment does until its catalogs' first 2.0 releases are deployed.
 * @property catalogsUpgrading catalogs still waiting for their first 2.0 release.
 */
data class GenerationReadiness(
    val idleEnvironments: List<Pair<EnvironmentKey, String>>,
    val catalogsUpgrading: Int,
) {
    val ready: Boolean get() = idleEnvironments.isEmpty() && catalogsUpgrading == 0
}

data class GetGenerationReadiness(
    override val tenantKey: TenantKey,
) : Query<GenerationReadiness>,
    RequiresPermission {
    override val permission get() = Permission.TEMPLATE_VIEW
}

@Component
class GetGenerationReadinessHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetGenerationReadiness, GenerationReadiness> {
    override fun handle(query: GetGenerationReadiness): GenerationReadiness = jdbi.withHandle<GenerationReadiness, Exception> { handle ->
        val idle = handle.createQuery(
            """
            SELECT e.id, e.name FROM environments e
            WHERE e.tenant_key = :t
              AND NOT EXISTS (SELECT 1 FROM environment_catalog_deployments d WHERE d.tenant_key = e.tenant_key AND d.environment_key = e.id)
              AND NOT EXISTS (SELECT 1 FROM environment_catalog_overrides o WHERE o.tenant_key = e.tenant_key AND o.environment_key = e.id)
            ORDER BY e.name
            """,
        )
            .bind("t", query.tenantKey)
            .map { rs, _ -> EnvironmentKey.of(rs.getString("id")) to rs.getString("name") }
            .list()
        val upgrading = handle.createQuery("SELECT count(*) FROM catalog_upgrade_queue WHERE tenant_key = :t")
            .bind("t", query.tenantKey)
            .mapTo(Int::class.java)
            .one()
        GenerationReadiness(idle, upgrading)
    }
}

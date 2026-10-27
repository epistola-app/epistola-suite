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
 * @property idleEnvironments environments that serve no catalog version, by name: every
 *   request naming one fails. A new environment starts so, and after an upgrade from 1.x every
 *   environment does until its catalogs' first 2.0 releases are deployed.
 * @property publishedIn1x the catalogs, by name, an upgrade from 1.x brought over that have no release
 *   yet (the upgrade cuts none, ADR 0035): publishing what they last published, their draft
 *   [PUBLISHED_IN_1X] where they have one, else their next draft, and deploying it restores what was
 *   live.
 */
data class GenerationReadiness(
    val idleEnvironments: List<Pair<EnvironmentKey, String>>,
    val publishedIn1x: List<String>,
) {
    val ready: Boolean get() = idleEnvironments.isEmpty() && publishedIn1x.isEmpty()
}

/** The name of the draft the 2.0 upgrade makes of what a 1.x catalog last published (`V20261101000900`). */
const val PUBLISHED_IN_1X = "Published in 1.x"

/** The `app_metadata` key the 2.0 upgrade lists its authored 1.x catalogs under (`V20261101000900`). */
const val ONE_X_CATALOGS = "upgrade-1x-catalogs"

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
            ORDER BY e.name
            """,
        )
            .bind("t", query.tenantKey)
            .map { rs, _ -> EnvironmentKey.of(rs.getString("id")) to rs.getString("name") }
            .list()
        val publishedIn1x = handle.createQuery(
            """
            SELECT c.name FROM catalogs c
            JOIN app_metadata m ON m.key = :key
                AND m.value @> jsonb_build_array(jsonb_build_object('tenant', c.tenant_key, 'catalog', c.catalog_id::text))
            WHERE c.tenant_key = :t
              AND NOT EXISTS (SELECT 1 FROM catalog_versions r WHERE r.tenant_key = c.tenant_key AND r.catalog_id = c.catalog_id AND r.state = 'released')
            ORDER BY c.name
            """,
        )
            .bind("t", query.tenantKey)
            .bind("key", ONE_X_CATALOGS)
            .mapTo(String::class.java)
            .list()
        GenerationReadiness(idle, publishedIn1x)
    }
}

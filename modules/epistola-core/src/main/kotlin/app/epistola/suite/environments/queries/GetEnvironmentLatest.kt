// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.environments.queries

import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.environments.EnvironmentOverrideStore
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
 * serves or follows: its override, else the highest release it serves. Generation decides the same
 * way ([ReleaseTargetResolver.select]); this is that answer, for showing it.
 */
data class GetEnvironmentLatest(
    override val tenantKey: TenantKey,
    val environmentKey: EnvironmentKey,
) : Query<Map<CatalogKey, CatalogSelection>>,
    RequiresPermission {
    override val permission get() = Permission.TEMPLATE_VIEW
}

@Component
class GetEnvironmentLatestHandler(
    private val jdbi: Jdbi,
    private val resolver: ReleaseTargetResolver,
) : QueryHandler<GetEnvironmentLatest, Map<CatalogKey, CatalogSelection>> {
    override fun handle(query: GetEnvironmentLatest): Map<CatalogKey, CatalogSelection> {
        val catalogs = jdbi.withHandle<List<CatalogKey>, Exception> { handle ->
            val served = handle.createQuery(
                """
                SELECT DISTINCT c.catalog_key FROM environment_catalog_deployments d
                JOIN catalogs c ON c.tenant_key = d.tenant_key AND c.catalog_id = d.catalog_id
                WHERE d.tenant_key = :t AND d.environment_key = :e
                """,
            )
                .bind("t", query.tenantKey).bind("e", query.environmentKey)
                .mapTo(String::class.java).list().map { CatalogKey.of(it) }
            (served + EnvironmentOverrideStore.list(handle, query.tenantKey, query.environmentKey, null).map { it.catalogKey }).distinct()
        }
        return catalogs.associateWith { resolver.select(query.tenantKey, it, query.environmentKey, null) }
    }
}

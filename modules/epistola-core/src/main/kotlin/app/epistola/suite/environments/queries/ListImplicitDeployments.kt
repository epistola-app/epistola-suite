// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.environments.queries

import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.environments.ReleaseDependencies
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * What an environment uses without deploying it: the releases the releases it serves are pinned to,
 * directly or transitively, that it does not serve itself. A pinned release renders from the tenant's
 * copy whatever the environment serves, so these come along with what is deployed. By catalog.
 */
data class ListImplicitDeployments(
    override val tenantKey: TenantKey,
    val environmentKey: EnvironmentKey,
) : Query<List<ImplicitDeployment>>,
    RequiresPermission {
    override val permission get() = Permission.TEMPLATE_VIEW
}

/** A release an environment uses because [neededBy] (catalog keys of what it serves) are pinned to it. */
data class ImplicitDeployment(val catalogKey: CatalogKey, val version: String, val neededBy: List<CatalogKey>)

@Component
class ListImplicitDeploymentsHandler(
    private val jdbi: Jdbi,
    private val dependencies: ReleaseDependencies,
) : QueryHandler<ListImplicitDeployments, List<ImplicitDeployment>> {
    override fun handle(query: ListImplicitDeployments): List<ImplicitDeployment> = jdbi.withHandle<List<ImplicitDeployment>, Exception> { handle ->
        val served = dependencies.served(handle, query.tenantKey, query.environmentKey)
        val neededBy = linkedMapOf<Pair<CatalogKey, String>, MutableList<CatalogKey>>()
        for (release in served) {
            for (needed in dependencies.needed(handle, query.tenantKey, listOf(release))) {
                if (needed !in served) neededBy.getOrPut(needed) { mutableListOf() }.add(release.first)
            }
        }
        neededBy.map { (release, by) -> ImplicitDeployment(release.first, release.second, by.distinct()) }
            .sortedWith(compareBy({ it.catalogKey.value }, { it.version }))
    }
}

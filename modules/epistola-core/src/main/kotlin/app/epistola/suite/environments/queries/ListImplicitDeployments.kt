// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.environments.queries

import app.epistola.suite.catalog.CatalogAddress
import app.epistola.suite.catalog.CatalogNames
import app.epistola.suite.catalog.dependencies.CatalogDependencyStore
import app.epistola.suite.catalog.releases.DraftRef
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.environments.DraftDeploymentStore
import app.epistola.suite.environments.ReleaseDependencies
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * What an environment uses without deploying it: the releases the versions it serves are pinned to,
 * directly or transitively, that it does not serve itself. A deployed draft's are what its
 * declarations pin. A pinned release renders from the tenant's
 * copy whatever the environment serves, so these come along with what is deployed. By catalog.
 */
data class ListImplicitDeployments(
    override val tenantKey: TenantKey,
    val environmentKey: EnvironmentKey,
) : Query<List<ImplicitDeployment>>,
    RequiresPermission {
    override val permission get() = Permission.TEMPLATE_VIEW
}

/**
 * A release an environment uses because [neededBy] (the catalogs of what it serves) are pinned to it.
 * Each catalog by namespace and key, since two can share a key (ADR 0034).
 */
data class ImplicitDeployment(val catalog: CatalogAddress, val version: String, val neededBy: List<CatalogAddress>) {
    val catalogKey: CatalogKey get() = catalog.key
}

@Component
class ListImplicitDeploymentsHandler(
    private val jdbi: Jdbi,
    private val dependencies: ReleaseDependencies,
    private val dependencyStore: CatalogDependencyStore,
) : QueryHandler<ListImplicitDeployments, List<ImplicitDeployment>> {
    override fun handle(query: ListImplicitDeployments): List<ImplicitDeployment> = jdbi.withHandle<List<ImplicitDeployment>, Exception> { handle ->
        val served = dependencies.served(handle, query.tenantKey, query.environmentKey)
        val neededBy = linkedMapOf<Pair<CatalogAddress, String>, MutableList<CatalogAddress>>()
        for (release in served) {
            for (needed in dependencies.needed(handle, query.tenantKey, listOf(release))) {
                if (needed !in served) neededBy.getOrPut(needed) { mutableListOf() }.add(release.first)
            }
        }
        for (draft in DraftDeploymentStore.list(handle, query.tenantKey, query.environmentKey, null)) {
            val catalog = CatalogAddress(draft.namespace, draft.catalogKey)
            val versionId = CatalogNames.on(handle, draft.catalogKey, draft.namespace) {
                DraftDeploymentStore.draft(handle, query.tenantKey, draft.catalogKey, DraftRef.Id(draft.draftId))?.first
            } ?: continue
            val pins = dependencyStore.declarationsOf(handle, query.tenantKey, versionId)
                .mapNotNull { declaration -> declaration.releaseVersion?.let { CatalogAddress(declaration.namespace, CatalogKey.of(declaration.catalogKey)) to it } }
            for (needed in dependencies.needed(handle, query.tenantKey, pins) + pins) {
                if (needed !in served) neededBy.getOrPut(needed) { mutableListOf() }.add(catalog)
            }
        }
        neededBy.map { (release, by) -> ImplicitDeployment(release.first, release.second, by.distinct()) }
            .sortedWith(compareBy({ it.catalogKey.value }, { it.catalog.namespace }, { it.version }))
    }
}

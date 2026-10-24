// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.environments.queries

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.environments.DraftDeployment
import app.epistola.suite.environments.DraftDeploymentStore
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * The drafts environments serve, live (contract ADR 0003): of [environmentKey] or every environment,
 * of [catalogKey] or every catalog. At most one per catalog per environment.
 */
data class ListDraftDeployments(
    override val tenantKey: TenantKey,
    val catalog: CatalogId? = null,
    val environmentKey: EnvironmentKey? = null,
) : Query<List<DraftDeployment>>,
    RequiresPermission {
    init {
        validate("catalog", catalog == null || catalog.tenantKey == tenantKey) { "The catalog is of the tenant" }
    }

    val catalogKey: CatalogKey? get() = catalog?.key
    override val permission get() = Permission.TEMPLATE_VIEW
}

@Component
class ListDraftDeploymentsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListDraftDeployments, List<DraftDeployment>> {
    override fun handle(query: ListDraftDeployments): List<DraftDeployment> = jdbi.withHandle<List<DraftDeployment>, Exception> { handle ->
        DraftDeploymentStore.list(handle, query.tenantKey, query.environmentKey, query.catalog)
    }
}

// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.environments.queries

import app.epistola.suite.common.PageRequest
import app.epistola.suite.common.Paging
import app.epistola.suite.common.ResultPage
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.environments.Environment
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class ListEnvironments(
    val tenantId: TenantId,
    val searchTerm: String? = null,
) : Query<List<Environment>>,
    RequiresPermission {
    override val permission get() = Permission.TEMPLATE_VIEW
    override val tenantKey get() = tenantId.key
}

/** [ListEnvironments], one page of it, cut in the database. */
data class ListEnvironmentPage(
    val tenantId: TenantId,
    val searchTerm: String? = null,
    val page: PageRequest,
) : Query<ResultPage<Environment>>,
    RequiresPermission {
    override val permission get() = Permission.TEMPLATE_VIEW
    override val tenantKey get() = tenantId.key
}

@Component
class ListEnvironmentsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListEnvironments, List<Environment>> {
    override fun handle(query: ListEnvironments): List<Environment> = jdbi.withHandle<List<Environment>, Exception> { handle ->
        EnvironmentList.query(handle, query.tenantId, query.searchTerm, paged = false).mapTo<Environment>().list()
    }
}

@Component
class ListEnvironmentPageHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListEnvironmentPage, ResultPage<Environment>> {
    override fun handle(query: ListEnvironmentPage): ResultPage<Environment> = jdbi.withHandle<ResultPage<Environment>, Exception> { handle ->
        Paging.read(query.page, Environment::class.java) { EnvironmentList.query(handle, query.tenantId, query.searchTerm, paged = true) }
    }
}

/** A tenant's environments by name, matching [searchTerm] in name or key; [paged] selects one page of them. */
private object EnvironmentList {
    fun query(handle: Handle, tenantId: TenantId, searchTerm: String?, paged: Boolean): org.jdbi.v3.core.statement.Query {
        val sql = buildString {
            append("SELECT id, tenant_key, name, production, created_at, updated_at, created_by, updated_by, revision")
            if (paged) append(", ${Paging.TOTAL}")
            append(" FROM environments WHERE tenant_key = :tenantId")
            if (searchTerm != null) append(" AND (LOWER(name) LIKE LOWER(:searchTerm) OR LOWER(id) LIKE LOWER(:searchTerm))")
            append(" ORDER BY name ASC, id")
            if (paged) append(" ${Paging.LIMIT_OFFSET}")
        }
        val q = handle.createQuery(sql).bind("tenantId", tenantId.key)
        if (searchTerm != null) q.bind("searchTerm", "%$searchTerm%")
        return q
    }
}

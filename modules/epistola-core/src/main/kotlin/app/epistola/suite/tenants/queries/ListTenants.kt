// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.tenants.queries

import app.epistola.suite.common.PageRequest
import app.epistola.suite.common.Paging
import app.epistola.suite.common.ResultPage
import app.epistola.suite.config.withHandle
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.PlatformRole
import app.epistola.suite.security.RequiresAuthentication
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.themes.TENANT_COLUMNS_WITH_THEME
import app.epistola.suite.themes.TENANT_THEME_JOIN
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class ListTenants(
    val searchTerm: String? = null,
    val idPrefix: String? = null,
) : Query<List<Tenant>>,
    RequiresAuthentication

/** [ListTenants], one page of it, cut in the database. */
data class ListTenantPage(
    val searchTerm: String? = null,
    val idPrefix: String? = null,
    val page: PageRequest,
) : Query<ResultPage<Tenant>>,
    RequiresAuthentication

@Component
class ListTenantsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListTenants, List<Tenant>> {
    override fun handle(query: ListTenants): List<Tenant> = jdbi.withHandle<List<Tenant>, Exception> { handle ->
        TenantList.query(handle, query.searchTerm, query.idPrefix, paged = false).mapTo<Tenant>().list()
    }
}

@Component
class ListTenantPageHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListTenantPage, ResultPage<Tenant>> {
    override fun handle(query: ListTenantPage): ResultPage<Tenant> = jdbi.withHandle<ResultPage<Tenant>, Exception> { handle ->
        Paging.read(query.page, Tenant::class.java) { TenantList.query(handle, query.searchTerm, query.idPrefix, paged = true) }
    }
}

/**
 * The tenants the current principal may see, newest first: every tenant for a global role or a tenant
 * manager, its memberships for anyone else. [paged] selects one page of them.
 */
private object TenantList {
    fun query(handle: Handle, searchTerm: String?, idPrefix: String?, paged: Boolean): org.jdbi.v3.core.statement.Query {
        val principal = SecurityContext.current()
        val hasFullAccess = principal.globalRoles.isNotEmpty() ||
            principal.hasPlatformRole(PlatformRole.TENANT_MANAGER)
        val accessibleTenantKeys = if (!hasFullAccess) principal.tenantMemberships.keys.map { it.value } else null
        val sql = buildString {
            append("SELECT $TENANT_COLUMNS_WITH_THEME")
            if (paged) append(", ${Paging.TOTAL}")
            append(" FROM tenants t $TENANT_THEME_JOIN WHERE 1=1")
            if (accessibleTenantKeys != null) append(" AND t.id = ANY(:tenantKeys)")
            if (!searchTerm.isNullOrBlank()) append(" AND t.name ILIKE :searchTerm")
            if (!idPrefix.isNullOrBlank()) append(" AND t.id LIKE :idPrefix")
            append(" ORDER BY t.created_at DESC, t.id")
            if (paged) append(" ${Paging.LIMIT_OFFSET}")
        }
        val q = handle.createQuery(sql)
        if (accessibleTenantKeys != null) q.bind("tenantKeys", accessibleTenantKeys.toTypedArray())
        if (!searchTerm.isNullOrBlank()) q.bind("searchTerm", "%$searchTerm%")
        if (!idPrefix.isNullOrBlank()) q.bind("idPrefix", "$idPrefix%")
        return q
    }
}

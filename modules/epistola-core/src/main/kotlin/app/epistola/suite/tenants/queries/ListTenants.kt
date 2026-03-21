package app.epistola.suite.tenants.queries

import app.epistola.suite.config.withHandle
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.PlatformRole
import app.epistola.suite.security.RequiresAuthentication
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.tenants.Tenant
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class ListTenants(
    val searchTerm: String? = null,
    val idPrefix: String? = null,
) : Query<List<Tenant>>,
    RequiresAuthentication

@Component
class ListTenantsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListTenants, List<Tenant>> {
    override fun handle(query: ListTenants): List<Tenant> = jdbi.withHandle<List<Tenant>, Exception> { handle ->
        val principal = SecurityContext.current()
        val hasFullAccess = principal.globalRoles.isNotEmpty() ||
            principal.hasPlatformRole(PlatformRole.TENANT_MANAGER)
        val accessibleTenantKeys = if (!hasFullAccess) {
            principal.tenantMemberships.keys.map { it.value }
        } else {
            null
        }

        val sql = buildString {
            append("SELECT * FROM tenants WHERE 1=1")
            if (accessibleTenantKeys != null) {
                append(" AND id = ANY(:tenantKeys)")
            }
            if (!query.searchTerm.isNullOrBlank()) {
                append(" AND name ILIKE :searchTerm")
            }
            if (!query.idPrefix.isNullOrBlank()) {
                append(" AND id LIKE :idPrefix")
            }
            append(" ORDER BY created_at DESC")
        }

        val jdbiQuery = handle.createQuery(sql)
        if (accessibleTenantKeys != null) {
            jdbiQuery.bind("tenantKeys", accessibleTenantKeys.toTypedArray())
        }
        if (!query.searchTerm.isNullOrBlank()) {
            jdbiQuery.bind("searchTerm", "%${query.searchTerm}%")
        }
        if (!query.idPrefix.isNullOrBlank()) {
            jdbiQuery.bind("idPrefix", "${query.idPrefix}%")
        }
        jdbiQuery
            .mapTo<Tenant>()
            .list()
    }
}

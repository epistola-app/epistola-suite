package app.epistola.suite.tenants.queries

import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.tenants.Tenant
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class ListTenants(
    val searchTerm: String? = null,
) : Query<List<Tenant>>

@Component
class ListTenantsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListTenants, List<Tenant>> {
    override fun handle(query: ListTenants): List<Tenant> = jdbi.withHandle<List<Tenant>, Exception> { handle ->
        val sql = buildString {
            append("SELECT id, name, default_theme_id, created_at FROM tenants WHERE 1=1")
            if (!query.searchTerm.isNullOrBlank()) {
                append(" AND name ILIKE :searchTerm")
            }
            append(" ORDER BY created_at DESC")
        }

        val jdbiQuery = handle.createQuery(sql)
        if (!query.searchTerm.isNullOrBlank()) {
            jdbiQuery.bind("searchTerm", "%${query.searchTerm}%")
        }
        jdbiQuery
            .mapTo<Tenant>()
            .list()
    }
}

package app.epistola.suite.tenants.queries

import app.epistola.suite.tenants.Tenant
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class ListTenants(
    val searchTerm: String? = null,
)

@Component
class ListTenantsHandler(private val jdbi: Jdbi) {
    fun handle(query: ListTenants): List<Tenant> = jdbi.withHandle<List<Tenant>, Exception> { handle ->
        val sql = buildString {
            append("SELECT id, name, created_at FROM tenants WHERE 1=1")
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

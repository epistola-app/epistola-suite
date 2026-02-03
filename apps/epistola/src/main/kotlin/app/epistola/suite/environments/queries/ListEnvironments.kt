package app.epistola.suite.environments.queries

import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.environments.Environment
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class ListEnvironments(
    val tenantId: TenantId,
    val searchTerm: String? = null,
) : Query<List<Environment>>

@Component
class ListEnvironmentsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListEnvironments, List<Environment>> {
    override fun handle(query: ListEnvironments): List<Environment> = jdbi.withHandle<List<Environment>, Exception> { handle ->
        val sql = if (query.searchTerm != null) {
            """
                SELECT id, tenant_id, name, created_at
                FROM environments
                WHERE tenant_id = :tenantId
                  AND (LOWER(name) LIKE LOWER(:searchTerm) OR LOWER(id) LIKE LOWER(:searchTerm))
                ORDER BY name ASC
                """
        } else {
            """
                SELECT id, tenant_id, name, created_at
                FROM environments
                WHERE tenant_id = :tenantId
                ORDER BY name ASC
                """
        }

        val q = handle.createQuery(sql).bind("tenantId", query.tenantId)

        if (query.searchTerm != null) {
            q.bind("searchTerm", "%${query.searchTerm}%")
        }

        q.mapTo<Environment>().list()
    }
}

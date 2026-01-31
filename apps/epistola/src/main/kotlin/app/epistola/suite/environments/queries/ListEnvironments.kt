package app.epistola.suite.environments.queries

import app.epistola.suite.environments.Environment
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import java.util.UUID

data class ListEnvironments(
    val tenantId: UUID,
) : Query<List<Environment>>

@Component
class ListEnvironmentsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListEnvironments, List<Environment>> {
    override fun handle(query: ListEnvironments): List<Environment> = jdbi.withHandle<List<Environment>, Exception> { handle ->
        handle.createQuery(
            """
                SELECT id, tenant_id, name, created_at
                FROM environments
                WHERE tenant_id = :tenantId
                ORDER BY name ASC
                """,
        )
            .bind("tenantId", query.tenantId)
            .mapTo<Environment>()
            .list()
    }
}

package app.epistola.suite.environments.queries

import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.environments.Environment
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class GetEnvironment(
    val tenantId: TenantId,
    val id: EnvironmentId,
) : Query<Environment?>

@Component
class GetEnvironmentHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetEnvironment, Environment?> {
    override fun handle(query: GetEnvironment): Environment? = jdbi.withHandle<Environment?, Exception> { handle ->
        handle.createQuery(
            """
                SELECT id, tenant_id, name, created_at
                FROM environments
                WHERE id = :id AND tenant_id = :tenantId
                """,
        )
            .bind("id", query.id)
            .bind("tenantId", query.tenantId)
            .mapTo<Environment>()
            .findOne()
            .orElse(null)
    }
}

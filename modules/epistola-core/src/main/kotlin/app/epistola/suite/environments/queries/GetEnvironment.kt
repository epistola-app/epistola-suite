package app.epistola.suite.environments.queries

import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.environments.Environment
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class GetEnvironment(
    val id: EnvironmentId,
) : Query<Environment?>,
    RequiresPermission {
    override val permission get() = Permission.TEMPLATE_VIEW
    override val tenantKey get() = id.tenantKey
}

@Component
class GetEnvironmentHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetEnvironment, Environment?> {
    override fun handle(query: GetEnvironment): Environment? = jdbi.withHandle<Environment?, Exception> { handle ->
        handle.createQuery(
            """
                SELECT id, tenant_key, name, created_at
                FROM environments
                WHERE id = :id AND tenant_key = :tenantId
                """,
        )
            .bind("id", query.id.key)
            .bind("tenantId", query.id.tenantKey)
            .mapTo<Environment>()
            .findOne()
            .orElse(null)
    }
}

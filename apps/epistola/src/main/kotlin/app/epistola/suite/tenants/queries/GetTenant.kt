package app.epistola.suite.tenants.queries

import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.tenants.Tenant
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class GetTenant(
    val id: Long,
) : Query<Tenant?>

@Component
class GetTenantHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetTenant, Tenant?> {
    override fun handle(query: GetTenant): Tenant? = jdbi.withHandle<Tenant?, Exception> { handle ->
        handle.createQuery(
            """
                SELECT id, name, created_at
                FROM tenants
                WHERE id = :id
                """,
        )
            .bind("id", query.id)
            .mapTo<Tenant>()
            .findOne()
            .orElse(null)
    }
}

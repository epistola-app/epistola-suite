package app.epistola.suite.tenants.queries

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.config.findById
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.tenants.Tenant
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

data class GetTenant(
    val id: TenantKey,
) : Query<Tenant?>

@Component
class GetTenantHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetTenant, Tenant?> {
    override fun handle(query: GetTenant): Tenant? = jdbi.withHandle<Tenant?, Exception> { handle ->
        handle.findById<Tenant>("tenants", query.id)
    }
}

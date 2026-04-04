package app.epistola.suite.stencils.queries

import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.stencils.Stencil
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class GetStencil(
    val id: StencilId,
) : Query<Stencil?>,
    RequiresPermission {
    override val permission = Permission.STENCIL_VIEW
    override val tenantKey: TenantKey get() = id.tenantKey
}

@Component
class GetStencilHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetStencil, Stencil?> {
    override fun handle(query: GetStencil): Stencil? = jdbi.withHandle<Stencil?, Exception> { handle ->
        handle.createQuery(
            """
            SELECT id, tenant_key, name, description, tags, created_at, last_modified
            FROM stencils
            WHERE id = :id AND tenant_key = :tenantId
            """,
        )
            .bind("id", query.id.key)
            .bind("tenantId", query.id.tenantKey)
            .mapTo<Stencil>()
            .findOne()
            .orElse(null)
    }
}

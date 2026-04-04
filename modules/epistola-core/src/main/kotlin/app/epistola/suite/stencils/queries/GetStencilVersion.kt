package app.epistola.suite.stencils.queries

import app.epistola.suite.common.ids.StencilVersionId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.stencils.model.StencilVersion
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class GetStencilVersion(
    val versionId: StencilVersionId,
) : Query<StencilVersion?>,
    RequiresPermission {
    override val permission = Permission.STENCIL_VIEW
    override val tenantKey: TenantKey get() = versionId.tenantKey
}

@Component
class GetStencilVersionHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetStencilVersion, StencilVersion?> {
    override fun handle(query: GetStencilVersion): StencilVersion? = jdbi.withHandle<StencilVersion?, Exception> { handle ->
        handle.createQuery(
            """
            SELECT * FROM stencil_versions
            WHERE tenant_key = :tenantId AND stencil_key = :stencilId AND id = :versionId
            """,
        )
            .bind("tenantId", query.versionId.tenantKey)
            .bind("stencilId", query.versionId.stencilKey)
            .bind("versionId", query.versionId.key)
            .mapTo<StencilVersion>()
            .findOne()
            .orElse(null)
    }
}

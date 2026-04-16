package app.epistola.suite.stencils.queries

import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.stencils.model.StencilVersionStatus
import app.epistola.suite.stencils.model.StencilVersionSummary
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class ListStencilVersions(
    val stencilId: StencilId,
    val status: StencilVersionStatus? = null,
) : Query<List<StencilVersionSummary>>,
    RequiresPermission {
    override val permission = Permission.STENCIL_VIEW
    override val tenantKey: TenantKey get() = stencilId.tenantKey
}

@Component
class ListStencilVersionsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListStencilVersions, List<StencilVersionSummary>> {
    override fun handle(query: ListStencilVersions): List<StencilVersionSummary> = jdbi.withHandle<List<StencilVersionSummary>, Exception> { handle ->
        val sql = buildString {
            append("SELECT id, status, created_at, published_at, archived_at FROM stencil_versions WHERE tenant_key = :tenantId AND catalog_key = :catalogKey AND stencil_key = :stencilId")
            if (query.status != null) {
                append(" AND status = :status")
            }
            append(" ORDER BY id DESC")
        }

        val jdbiQuery = handle.createQuery(sql)
            .bind("tenantId", query.stencilId.tenantKey)
            .bind("catalogKey", query.stencilId.catalogKey)
            .bind("stencilId", query.stencilId.key)
        if (query.status != null) {
            jdbiQuery.bind("status", query.status.name.lowercase())
        }
        jdbiQuery.mapTo<StencilVersionSummary>().list()
    }
}

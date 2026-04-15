package app.epistola.suite.stencils.queries

import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Finds template names that reference a given stencil in their draft or published versions.
 * Used to check if a stencil is in use before deletion.
 */
data class FindStencilUsages(
    val stencilId: StencilId,
) : Query<List<String>>,
    RequiresPermission {
    override val permission get() = Permission.STENCIL_VIEW
    override val tenantKey: TenantKey get() = stencilId.tenantKey
}

@Component
class FindStencilUsagesHandler(
    private val jdbi: Jdbi,
) : QueryHandler<FindStencilUsages, List<String>> {

    override fun handle(query: FindStencilUsages): List<String> = jdbi.withHandle<List<String>, Exception> { handle ->
        findStencilUsages(handle, query.stencilId)
    }

    companion object {
        /**
         * Reusable function for checking stencil usages inside an existing transaction/handle.
         */
        fun findStencilUsages(handle: Handle, stencilId: StencilId): List<String> = handle.createQuery(
            """
            SELECT DISTINCT dt.name
            FROM template_versions tv
            JOIN document_templates dt ON dt.tenant_key = tv.tenant_key AND dt.catalog_key = tv.catalog_key AND dt.id = tv.template_key
            CROSS JOIN LATERAL jsonb_each(tv.template_model -> 'nodes') AS n(key, value)
            WHERE tv.tenant_key = :tenantId
              AND tv.status IN ('draft', 'published')
              AND n.value ->> 'type' = 'stencil'
              AND n.value -> 'props' ->> 'stencilId' = :stencilId
            ORDER BY dt.name
            """,
        )
            .bind("tenantId", stencilId.tenantKey)
            .bind("stencilId", stencilId.key.value)
            .mapTo(String::class.java)
            .list()
    }
}

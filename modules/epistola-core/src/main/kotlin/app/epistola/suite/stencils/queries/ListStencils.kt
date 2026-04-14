package app.epistola.suite.stencils.queries

import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.stencils.Stencil
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class ListStencils(
    val tenantId: TenantId,
    val searchTerm: String? = null,
    val tag: String? = null,
    val catalogKey: CatalogKey? = null,
    val limit: Int = 50,
    val offset: Int = 0,
) : Query<List<Stencil>>,
    RequiresPermission {
    override val permission = Permission.STENCIL_VIEW
    override val tenantKey: TenantKey get() = tenantId.key
}

@Component
class ListStencilsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListStencils, List<Stencil>> {
    override fun handle(query: ListStencils): List<Stencil> = jdbi.withHandle<List<Stencil>, Exception> { handle ->
        val sql = buildString {
            append("SELECT id, tenant_key, catalog_key, name, description, tags, created_at, last_modified FROM stencils WHERE tenant_key = :tenantId")
            if (query.catalogKey != null) {
                append(" AND catalog_key = :catalogKey")
            }
            if (!query.searchTerm.isNullOrBlank()) {
                append(" AND (name ILIKE :searchTerm OR description ILIKE :searchTerm)")
            }
            if (!query.tag.isNullOrBlank()) {
                append(" AND tags @> :tag::jsonb")
            }
            append(" ORDER BY last_modified DESC")
            append(" LIMIT :limit OFFSET :offset")
        }

        val jdbiQuery = handle.createQuery(sql)
            .bind("tenantId", query.tenantId.key)
        if (query.catalogKey != null) {
            jdbiQuery.bind("catalogKey", query.catalogKey)
        }
        if (!query.searchTerm.isNullOrBlank()) {
            jdbiQuery.bind("searchTerm", "%${query.searchTerm}%")
        }
        if (!query.tag.isNullOrBlank()) {
            jdbiQuery.bind("tag", "[\"${query.tag}\"]")
        }
        jdbiQuery
            .bind("limit", query.limit)
            .bind("offset", query.offset)
            .mapTo<Stencil>()
            .list()
    }
}

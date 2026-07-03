package app.epistola.suite.templates.queries

import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

/**
 * Total number of templates matching the same filters as [ListDocumentTemplates],
 * ignoring pagination — the `totalElements` for the paginated REST list envelope.
 */
data class CountDocumentTemplates(
    val tenantId: TenantId,
    val searchTerm: String? = null,
    val catalogKey: CatalogKey? = null,
) : Query<Long>,
    RequiresPermission {
    override val permission: Permission get() = Permission.TEMPLATE_VIEW
    override val tenantKey: TenantKey get() = tenantId.key
}

@Component
class CountDocumentTemplatesHandler(
    private val jdbi: Jdbi,
) : QueryHandler<CountDocumentTemplates, Long> {
    override fun handle(query: CountDocumentTemplates): Long = jdbi.withHandle<Long, Exception> { handle ->
        val sql = buildString {
            append("SELECT COUNT(*) FROM document_templates dt WHERE dt.tenant_key = :tenantId")
            if (query.catalogKey != null) {
                append(" AND dt.catalog_key = :catalogKey")
            }
            if (!query.searchTerm.isNullOrBlank()) {
                append(" AND dt.name ILIKE :searchTerm")
            }
        }
        val jdbiQuery = handle.createQuery(sql).bind("tenantId", query.tenantId.key)
        if (query.catalogKey != null) {
            jdbiQuery.bind("catalogKey", query.catalogKey)
        }
        if (!query.searchTerm.isNullOrBlank()) {
            jdbiQuery.bind("searchTerm", "%${query.searchTerm}%")
        }
        jdbiQuery.mapTo<Long>().one()
    }
}

package app.epistola.suite.templates.queries

import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.DocumentTemplate
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class ListDocumentTemplates(
    val tenantId: TenantId,
    val searchTerm: String? = null,
    val limit: Int = 50,
    val offset: Int = 0,
) : Query<List<DocumentTemplate>>,
    RequiresPermission {
    override val permission: Permission get() = Permission.TEMPLATE_VIEW
    override val tenantKey: TenantKey get() = tenantId.key
}

@Component
class ListDocumentTemplatesHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListDocumentTemplates, List<DocumentTemplate>> {
    override fun handle(query: ListDocumentTemplates): List<DocumentTemplate> = jdbi.withHandle<List<DocumentTemplate>, Exception> { handle ->
        val sql = buildString {
            append("SELECT dt.id, dt.tenant_key, dt.catalog_key, c.type AS catalog_type, dt.name, dt.theme_key, dt.theme_catalog_key, dt.schema, dt.data_model AS published_data_model, dt.data_examples AS published_data_examples, dt.draft_data_model, dt.draft_data_examples, dt.pdfa_enabled, dt.created_at, dt.last_modified FROM document_templates dt JOIN catalogs c ON c.tenant_key = dt.tenant_key AND c.id = dt.catalog_key WHERE dt.tenant_key = :tenantId")
            if (!query.searchTerm.isNullOrBlank()) {
                append(" AND dt.name ILIKE :searchTerm")
            }
            append(" ORDER BY dt.last_modified DESC")
            append(" LIMIT :limit OFFSET :offset")
        }

        val jdbiQuery = handle.createQuery(sql)
            .bind("tenantId", query.tenantId.key)
        if (!query.searchTerm.isNullOrBlank()) {
            jdbiQuery.bind("searchTerm", "%${query.searchTerm}%")
        }
        jdbiQuery
            .bind("limit", query.limit)
            .bind("offset", query.offset)
            .mapTo<DocumentTemplate>()
            .list()
    }
}

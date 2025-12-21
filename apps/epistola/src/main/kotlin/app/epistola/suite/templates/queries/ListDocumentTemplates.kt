package app.epistola.suite.templates.queries

import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.templates.DocumentTemplate
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class ListDocumentTemplates(
    val tenantId: Long,
    val searchTerm: String? = null,
    val limit: Int = 50,
    val offset: Int = 0,
) : Query<List<DocumentTemplate>>

@Component
class ListDocumentTemplatesHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListDocumentTemplates, List<DocumentTemplate>> {
    override fun handle(query: ListDocumentTemplates): List<DocumentTemplate> = jdbi.withHandle<List<DocumentTemplate>, Exception> { handle ->
        val sql = buildString {
            append("SELECT id, tenant_id, name, template_model, data_model, data_examples, created_at, last_modified FROM document_templates WHERE tenant_id = :tenantId")
            if (!query.searchTerm.isNullOrBlank()) {
                append(" AND name ILIKE :searchTerm")
            }
            append(" ORDER BY last_modified DESC")
            append(" LIMIT :limit OFFSET :offset")
        }

        val jdbiQuery = handle.createQuery(sql)
            .bind("tenantId", query.tenantId)
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

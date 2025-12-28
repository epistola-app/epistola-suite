package app.epistola.suite.templates.queries

import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.templates.DocumentTemplate
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class GetDocumentTemplate(
    val tenantId: Long,
    val id: Long,
) : Query<DocumentTemplate?>

@Component
class GetDocumentTemplateHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetDocumentTemplate, DocumentTemplate?> {
    override fun handle(query: GetDocumentTemplate): DocumentTemplate? = jdbi.withHandle<DocumentTemplate?, Exception> { handle ->
        handle.createQuery(
            """
                SELECT id, tenant_id, name, data_model, data_examples, created_at, last_modified
                FROM document_templates
                WHERE id = :id AND tenant_id = :tenantId
                """,
        )
            .bind("id", query.id)
            .bind("tenantId", query.tenantId)
            .mapTo<DocumentTemplate>()
            .findOne()
            .orElse(null)
    }
}

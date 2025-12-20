package app.epistola.suite.templates.queries

import app.epistola.suite.templates.DocumentTemplate
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class GetDocumentTemplate(
    val id: Long,
)

@Component
class GetDocumentTemplateHandler(private val jdbi: Jdbi) {
    fun handle(query: GetDocumentTemplate): DocumentTemplate? = jdbi.withHandle<DocumentTemplate?, Exception> { handle ->
        handle.createQuery(
            """
                SELECT id, name, content, created_at, last_modified
                FROM document_templates
                WHERE id = :id
                """,
        )
            .bind("id", query.id)
            .mapTo<DocumentTemplate>()
            .findOne()
            .orElse(null)
    }
}

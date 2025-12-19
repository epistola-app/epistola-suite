package app.epistola.suite.templates.queries

import app.epistola.suite.templates.DocumentTemplate
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class ListDocumentTemplates(
    val limit: Int = 50,
    val offset: Int = 0,
)

@Component
class ListDocumentTemplatesHandler(private val jdbi: Jdbi) {
    fun handle(query: ListDocumentTemplates): List<DocumentTemplate> {
        return jdbi.withHandle<List<DocumentTemplate>, Exception> { handle ->
            handle.createQuery(
                """
                SELECT id, name, content, created_at, last_modified
                FROM document_templates
                ORDER BY last_modified DESC
                LIMIT :limit OFFSET :offset
                """,
            )
                .bind("limit", query.limit)
                .bind("offset", query.offset)
                .mapTo<DocumentTemplate>()
                .list()
        }
    }
}

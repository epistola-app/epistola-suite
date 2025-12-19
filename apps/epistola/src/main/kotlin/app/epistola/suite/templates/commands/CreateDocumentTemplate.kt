package app.epistola.suite.templates.commands

import app.epistola.suite.templates.DocumentTemplate
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class CreateDocumentTemplate(
    val name: String,
    val content: String?,
)

@Component
class CreateDocumentTemplateHandler(private val jdbi: Jdbi) {
    fun handle(command: CreateDocumentTemplate): DocumentTemplate {
        return jdbi.withHandle<DocumentTemplate, Exception> { handle ->
            handle.createQuery(
                """
                INSERT INTO document_templates (name, content, created_at, last_modified)
                VALUES (:name, :content, NOW(), NOW())
                RETURNING *
                """,
            )
                .bind("name", command.name)
                .bind("content", command.content)
                .mapTo<DocumentTemplate>()
                .one()
        }
    }
}

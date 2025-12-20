package app.epistola.suite.templates.commands

import app.epistola.suite.templates.DocumentTemplate
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class UpdateDocumentTemplate(
    val id: Long,
    val content: String,
)

@Component
class UpdateDocumentTemplateHandler(private val jdbi: Jdbi) {
    fun handle(command: UpdateDocumentTemplate): DocumentTemplate? = jdbi.withHandle<DocumentTemplate?, Exception> { handle ->
        handle.createQuery(
            """
                UPDATE document_templates
                SET content = :content, last_modified = NOW()
                WHERE id = :id
                RETURNING *
                """,
        )
            .bind("id", command.id)
            .bind("content", command.content)
            .mapTo<DocumentTemplate>()
            .findOne()
            .orElse(null)
    }
}

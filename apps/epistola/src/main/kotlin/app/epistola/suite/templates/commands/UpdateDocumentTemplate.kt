package app.epistola.suite.templates.commands

import app.epistola.suite.templates.DocumentTemplate
import app.epistola.suite.templates.model.EditorTemplate
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

data class UpdateDocumentTemplate(
    val tenantId: Long,
    val id: Long,
    val content: EditorTemplate,
)

@Component
class UpdateDocumentTemplateHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
) {
    fun handle(command: UpdateDocumentTemplate): DocumentTemplate? {
        val contentJson = objectMapper.writeValueAsString(command.content)

        return jdbi.withHandle<DocumentTemplate?, Exception> { handle ->
            handle.createQuery(
                """
                UPDATE document_templates
                SET content = :content::jsonb, last_modified = NOW()
                WHERE id = :id AND tenant_id = :tenantId
                RETURNING *
                """,
            )
                .bind("id", command.id)
                .bind("tenantId", command.tenantId)
                .bind("content", contentJson)
                .mapTo<DocumentTemplate>()
                .findOne()
                .orElse(null)
        }
    }
}

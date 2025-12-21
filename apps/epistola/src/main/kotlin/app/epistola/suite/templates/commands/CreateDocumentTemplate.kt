package app.epistola.suite.templates.commands

import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.templates.DocumentTemplate
import app.epistola.suite.templates.model.Margins
import app.epistola.suite.templates.model.PageSettings
import app.epistola.suite.templates.model.TemplateModel
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.util.UUID

data class CreateDocumentTemplate(
    val tenantId: Long,
    val name: String,
) : Command<DocumentTemplate> {
    init {
        validate("name", name.isNotBlank()) { "Name is required" }
        validate("name", name.length <= 255) { "Name must be 255 characters or less" }
    }
}

@Component
class CreateDocumentTemplateHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
) : CommandHandler<CreateDocumentTemplate, DocumentTemplate> {
    override fun handle(command: CreateDocumentTemplate): DocumentTemplate {
        // Create a default TemplateModel for new documents
        val templateModel = TemplateModel(
            id = UUID.randomUUID().toString(),
            name = command.name,
            version = 1,
            pageSettings = PageSettings(margins = Margins()),
            blocks = emptyList(),
        )

        val templateModelJson = objectMapper.writeValueAsString(templateModel)

        return jdbi.withHandle<DocumentTemplate, Exception> { handle ->
            handle.createQuery(
                """
                INSERT INTO document_templates (tenant_id, name, template_model, data_model, data_examples, created_at, last_modified)
                VALUES (:tenantId, :name, :templateModel::jsonb, NULL, '[]'::jsonb, NOW(), NOW())
                RETURNING id, tenant_id, name, template_model, data_model, data_examples, created_at, last_modified
                """,
            )
                .bind("tenantId", command.tenantId)
                .bind("name", command.name)
                .bind("templateModel", templateModelJson)
                .mapTo<DocumentTemplate>()
                .one()
        }
    }
}

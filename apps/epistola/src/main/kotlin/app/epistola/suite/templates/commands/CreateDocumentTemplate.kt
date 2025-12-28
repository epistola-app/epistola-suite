package app.epistola.suite.templates.commands

import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.templates.DocumentTemplate
import app.epistola.suite.templates.model.Margins
import app.epistola.suite.templates.model.PageSettings
import app.epistola.suite.templates.model.TemplateModel
import app.epistola.suite.templates.validation.JsonSchemaValidator
import app.epistola.suite.templates.validation.SchemaValidationResult
import app.epistola.suite.validation.ValidationException
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.util.UUID

data class CreateDocumentTemplate(
    val tenantId: Long,
    val name: String,
    val schema: String? = null,
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
    private val jsonSchemaValidator: JsonSchemaValidator,
) : CommandHandler<CreateDocumentTemplate, DocumentTemplate> {
    override fun handle(command: CreateDocumentTemplate): DocumentTemplate {
        // Validate schema if provided
        command.schema?.let { schemaJson ->
            when (val result = jsonSchemaValidator.validateSchema(schemaJson)) {
                is SchemaValidationResult.Valid -> { /* Schema is valid */ }
                is SchemaValidationResult.Invalid -> throw ValidationException("schema", result.message)
            }
        }

        return jdbi.inTransaction<DocumentTemplate, Exception> { handle ->
            // 1. Create the template
            val template = handle.createQuery(
                """
                INSERT INTO document_templates (tenant_id, name, schema, data_model, data_examples, created_at, last_modified)
                VALUES (:tenantId, :name, :schema::jsonb, NULL, '[]'::jsonb, NOW(), NOW())
                RETURNING id, tenant_id, name, schema, data_model, data_examples, created_at, last_modified
                """,
            )
                .bind("tenantId", command.tenantId)
                .bind("name", command.name)
                .bind("schema", command.schema)
                .mapTo<DocumentTemplate>()
                .one()

            // 2. Create default variant
            val variantId = handle.createQuery(
                """
                INSERT INTO template_variants (template_id, tags, created_at, last_modified)
                VALUES (:templateId, '{}'::jsonb, NOW(), NOW())
                RETURNING id
                """,
            )
                .bind("templateId", template.id)
                .mapTo<Long>()
                .one()

            // 3. Create draft version with default TemplateModel
            val templateModel = TemplateModel(
                id = UUID.randomUUID().toString(),
                name = command.name,
                version = 1,
                pageSettings = PageSettings(margins = Margins()),
                blocks = emptyList(),
            )
            val templateModelJson = objectMapper.writeValueAsString(templateModel)

            handle.createUpdate(
                """
                INSERT INTO template_versions (variant_id, version_number, template_model, status, created_at)
                VALUES (:variantId, NULL, :templateModel::jsonb, 'draft', NOW())
                """,
            )
                .bind("variantId", variantId)
                .bind("templateModel", templateModelJson)
                .execute()

            template
        }
    }
}

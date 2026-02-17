package app.epistola.suite.templates.commands

import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.templates.DocumentTemplate
import app.epistola.suite.templates.model.Node
import app.epistola.suite.templates.model.Slot
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.suite.templates.validation.JsonSchemaValidator
import app.epistola.suite.templates.validation.SchemaValidationResult
import app.epistola.suite.validation.ValidationException
import app.epistola.suite.validation.validate
import app.epistola.template.model.ThemeRef
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

data class CreateDocumentTemplate(
    val id: TemplateId,
    val tenantId: TenantId,
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
                INSERT INTO document_templates (id, tenant_id, name, theme_id, schema, data_model, data_examples, created_at, last_modified)
                VALUES (:id, :tenantId, :name, NULL, :schema::jsonb, NULL, '[]'::jsonb, NOW(), NOW())
                RETURNING id, tenant_id, name, theme_id, schema, data_model, data_examples, created_at, last_modified
                """,
            )
                .bind("id", command.id)
                .bind("tenantId", command.tenantId)
                .bind("name", command.name)
                .bind("schema", command.schema)
                .mapTo<DocumentTemplate>()
                .one()

            // 2. Create default variant with template-specific ID to avoid conflicts
            val variantId = VariantId.of("${command.id}-default")
            handle.createUpdate(
                """
                INSERT INTO template_variants (id, tenant_id, template_id, attributes, is_default, created_at, last_modified)
                VALUES (:id, :tenantId, :templateId, '{}'::jsonb, TRUE, NOW(), NOW())
                """,
            )
                .bind("id", variantId)
                .bind("tenantId", command.tenantId)
                .bind("templateId", template.id)
                .execute()

            // 3. Create draft version with default TemplateDocument (version ID = 1)
            val rootId = "root-${variantId.value}"
            val slotId = "slot-${variantId.value}"
            val templateModel = TemplateDocument(
                modelVersion = 1,
                root = rootId,
                nodes = mapOf(
                    rootId to Node(
                        id = rootId,
                        type = "root",
                        slots = listOf(slotId),
                    ),
                ),
                slots = mapOf(
                    slotId to Slot(
                        id = slotId,
                        nodeId = rootId,
                        name = "children",
                        children = emptyList(),
                    ),
                ),
                themeRef = ThemeRef.Inherit,
            )
            val templateModelJson = objectMapper.writeValueAsString(templateModel)
            val versionId = VersionId.of(1) // First version is always 1

            handle.createUpdate(
                """
                INSERT INTO template_versions (id, tenant_id, variant_id, template_model, status, created_at)
                VALUES (:id, :tenantId, :variantId, :templateModel::jsonb, 'draft', NOW())
                """,
            )
                .bind("id", versionId)
                .bind("tenantId", command.tenantId)
                .bind("variantId", variantId)
                .bind("templateModel", templateModelJson)
                .execute()

            template
        }
    }
}

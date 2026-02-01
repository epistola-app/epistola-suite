package app.epistola.suite.templates.commands

import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.templates.DocumentTemplate
import app.epistola.suite.templates.model.DataExample
import app.epistola.suite.templates.validation.DataModelValidationException
import app.epistola.suite.templates.validation.JsonSchemaValidator
import app.epistola.suite.templates.validation.ValidationError
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * Updates a single data example within a template.
 * Only validates the single example being updated against the schema.
 *
 * @property forceUpdate When true, validation warnings don't block the update.
 *                       Warnings are returned in the result instead of throwing.
 */
data class UpdateDataExample(
    val tenantId: TenantId,
    val templateId: TemplateId,
    val exampleId: String,
    val name: String? = null,
    val data: ObjectNode? = null,
    val forceUpdate: Boolean = false,
) : Command<UpdateDataExampleResult?>

/**
 * Result of updating a data example.
 *
 * @property example The updated data example
 * @property warnings Validation warnings (only populated when forceUpdate=true)
 */
data class UpdateDataExampleResult(
    val example: DataExample,
    val warnings: Map<String, List<ValidationError>> = emptyMap(),
)

@Component
class UpdateDataExampleHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
    private val jsonSchemaValidator: JsonSchemaValidator,
) : CommandHandler<UpdateDataExample, UpdateDataExampleResult?> {
    override fun handle(command: UpdateDataExample): UpdateDataExampleResult? {
        val existing = getExisting(command.tenantId, command.templateId) ?: return null

        // Find the example to update
        val existingExample = existing.dataExamples.find { it.id == command.exampleId }
            ?: return null

        // Build updated example
        val updatedExample = DataExample(
            id = existingExample.id,
            name = command.name ?: existingExample.name,
            data = command.data ?: existingExample.data,
        )

        // Validate only the updated example against schema
        val warnings = mutableMapOf<String, List<ValidationError>>()
        val schema = existing.dataModel

        if (schema != null) {
            val errors = jsonSchemaValidator.validateExamples(schema, listOf(updatedExample))
            if (errors.isNotEmpty()) {
                if (command.forceUpdate) {
                    warnings.putAll(errors)
                } else {
                    throw DataModelValidationException(errors)
                }
            }
        }

        // Replace the example in the list
        val updatedExamples = existing.dataExamples.map { example ->
            if (example.id == command.exampleId) updatedExample else example
        }

        // Persist
        val updated = updateDataExamples(command.tenantId, command.templateId, updatedExamples)
            ?: return null

        // Return the updated example from the persisted list
        val persistedExample = updated.dataExamples.find { it.id == command.exampleId }
            ?: return null

        return UpdateDataExampleResult(example = persistedExample, warnings = warnings)
    }

    private fun getExisting(tenantId: TenantId, templateId: TemplateId): DocumentTemplate? = jdbi.withHandle<DocumentTemplate?, Exception> { handle ->
        handle.createQuery(
            """
                SELECT id, tenant_id, name, data_model, data_examples, created_at, last_modified
                FROM document_templates
                WHERE id = :id AND tenant_id = :tenantId
                """,
        )
            .bind("id", templateId)
            .bind("tenantId", tenantId)
            .mapTo<DocumentTemplate>()
            .findOne()
            .orElse(null)
    }

    private fun updateDataExamples(
        tenantId: TenantId,
        templateId: TemplateId,
        dataExamples: List<DataExample>,
    ): DocumentTemplate? = jdbi.withHandle<DocumentTemplate?, Exception> { handle ->
        handle.createQuery(
            """
            UPDATE document_templates
            SET data_examples = :dataExamples::jsonb, last_modified = NOW()
            WHERE id = :id AND tenant_id = :tenantId
            RETURNING id, tenant_id, name, data_model, data_examples, created_at, last_modified
            """,
        )
            .bind("id", templateId)
            .bind("tenantId", tenantId)
            .bind("dataExamples", objectMapper.writeValueAsString(dataExamples))
            .mapTo<DocumentTemplate>()
            .findOne()
            .orElse(null)
    }
}

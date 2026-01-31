package app.epistola.suite.templates.commands

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
import java.util.UUID

/**
 * Updates a template's metadata (name, dataModel, dataExamples).
 * Note: templateModel is now stored in TemplateVersion and updated via version commands.
 *
 * @property forceUpdate When true, validation warnings don't block the update.
 *                       Warnings are returned in the result instead of throwing.
 */
data class UpdateDocumentTemplate(
    val tenantId: UUID,
    val id: UUID,
    val name: String? = null,
    val dataModel: ObjectNode? = null,
    val dataExamples: List<DataExample>? = null,
    val forceUpdate: Boolean = false,
) : Command<UpdateDocumentTemplateResult?>

/**
 * Result of updating a document template.
 *
 * @property template The updated template, or null if not found
 * @property warnings Validation warnings that occurred during update (only populated when forceUpdate=true)
 */
data class UpdateDocumentTemplateResult(
    val template: DocumentTemplate,
    val warnings: Map<String, List<ValidationError>> = emptyMap(),
)

@Component
class UpdateDocumentTemplateHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
    private val jsonSchemaValidator: JsonSchemaValidator,
) : CommandHandler<UpdateDocumentTemplate, UpdateDocumentTemplateResult?> {
    override fun handle(command: UpdateDocumentTemplate): UpdateDocumentTemplateResult? {
        // Validate examples against schema and collect warnings
        val warnings = mutableMapOf<String, List<ValidationError>>()
        val schemaToValidate = command.dataModel
        val examplesToValidate = command.dataExamples

        if (schemaToValidate != null && examplesToValidate != null && examplesToValidate.isNotEmpty()) {
            val errors = jsonSchemaValidator.validateExamples(schemaToValidate, examplesToValidate)
            if (errors.isNotEmpty()) {
                if (command.forceUpdate) {
                    warnings.putAll(errors)
                } else {
                    throw DataModelValidationException(errors)
                }
            }
        }

        // If only schema is being updated, validate existing examples against new schema
        if (schemaToValidate != null && examplesToValidate == null) {
            val existing = getExisting(command.tenantId, command.id) ?: return null
            if (existing.dataExamples.isNotEmpty()) {
                val errors = jsonSchemaValidator.validateExamples(schemaToValidate, existing.dataExamples)
                if (errors.isNotEmpty()) {
                    if (command.forceUpdate) {
                        warnings.putAll(errors)
                    } else {
                        throw DataModelValidationException(errors)
                    }
                }
            }
        }

        // If only examples are being updated, validate against existing schema
        if (schemaToValidate == null && examplesToValidate != null && examplesToValidate.isNotEmpty()) {
            val existing = getExisting(command.tenantId, command.id) ?: return null
            val existingSchema = existing.dataModel
            if (existingSchema != null) {
                val errors = jsonSchemaValidator.validateExamples(existingSchema, examplesToValidate)
                if (errors.isNotEmpty()) {
                    if (command.forceUpdate) {
                        warnings.putAll(errors)
                    } else {
                        throw DataModelValidationException(errors)
                    }
                }
            }
        }

        // Build dynamic UPDATE query
        val updates = mutableListOf<String>()
        val bindings = mutableMapOf<String, Any?>()

        if (command.name != null) {
            updates.add("name = :name")
            bindings["name"] = command.name
        }
        if (command.dataModel != null) {
            updates.add("data_model = :dataModel::jsonb")
            bindings["dataModel"] = objectMapper.writeValueAsString(command.dataModel)
        }
        if (command.dataExamples != null) {
            updates.add("data_examples = :dataExamples::jsonb")
            bindings["dataExamples"] = objectMapper.writeValueAsString(command.dataExamples)
        }

        if (updates.isEmpty()) {
            val existing = getExisting(command.tenantId, command.id) ?: return null
            return UpdateDocumentTemplateResult(template = existing, warnings = warnings)
        }

        updates.add("last_modified = NOW()")

        val sql = """
            UPDATE document_templates
            SET ${updates.joinToString(", ")}
            WHERE id = :id AND tenant_id = :tenantId
            RETURNING id, tenant_id, name, data_model, data_examples, created_at, last_modified
        """

        val updated = jdbi.withHandle<DocumentTemplate?, Exception> { handle ->
            val query = handle.createQuery(sql)
                .bind("id", command.id)
                .bind("tenantId", command.tenantId)

            bindings.forEach { (key, value) -> query.bind(key, value) }

            query.mapTo<DocumentTemplate>()
                .findOne()
                .orElse(null)
        } ?: return null

        return UpdateDocumentTemplateResult(template = updated, warnings = warnings)
    }

    private fun getExisting(tenantId: UUID, id: UUID): DocumentTemplate? = jdbi.withHandle<DocumentTemplate?, Exception> { handle ->
        handle.createQuery(
            """
            SELECT id, tenant_id, name, data_model, data_examples, created_at, last_modified
            FROM document_templates
            WHERE id = :id AND tenant_id = :tenantId
            """,
        )
            .bind("id", id)
            .bind("tenantId", tenantId)
            .mapTo<DocumentTemplate>()
            .findOne()
            .orElse(null)
    }
}

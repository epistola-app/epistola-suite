package app.epistola.suite.templates.commands

import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.templates.DocumentTemplate
import app.epistola.suite.templates.model.DataExample
import app.epistola.suite.templates.validation.DataModelValidationException
import app.epistola.suite.templates.validation.JsonSchemaValidator
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * Updates a template's metadata (name, dataModel, dataExamples).
 * Note: templateModel is now stored in TemplateVersion and updated via version commands.
 */
data class UpdateDocumentTemplate(
    val tenantId: Long,
    val id: Long,
    val name: String? = null,
    val dataModel: ObjectNode? = null,
    val dataExamples: List<DataExample>? = null,
) : Command<DocumentTemplate?>

@Component
class UpdateDocumentTemplateHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
    private val jsonSchemaValidator: JsonSchemaValidator,
) : CommandHandler<UpdateDocumentTemplate, DocumentTemplate?> {
    override fun handle(command: UpdateDocumentTemplate): DocumentTemplate? {
        // Validate examples against schema if both are being updated
        val schemaToValidate = command.dataModel
        val examplesToValidate = command.dataExamples

        if (schemaToValidate != null && examplesToValidate != null && examplesToValidate.isNotEmpty()) {
            val errors = jsonSchemaValidator.validateExamples(schemaToValidate, examplesToValidate)
            if (errors.isNotEmpty()) {
                throw DataModelValidationException(errors)
            }
        }

        // If only schema is being updated, validate existing examples against new schema
        if (schemaToValidate != null && examplesToValidate == null) {
            val existing = getExisting(command.tenantId, command.id) ?: return null
            if (existing.dataExamples.isNotEmpty()) {
                val errors = jsonSchemaValidator.validateExamples(schemaToValidate, existing.dataExamples)
                if (errors.isNotEmpty()) {
                    throw DataModelValidationException(errors)
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
                    throw DataModelValidationException(errors)
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
            return getExisting(command.tenantId, command.id)
        }

        updates.add("last_modified = NOW()")

        val sql = """
            UPDATE document_templates
            SET ${updates.joinToString(", ")}
            WHERE id = :id AND tenant_id = :tenantId
            RETURNING id, tenant_id, name, data_model, data_examples, created_at, last_modified
        """

        return jdbi.withHandle<DocumentTemplate?, Exception> { handle ->
            val query = handle.createQuery(sql)
                .bind("id", command.id)
                .bind("tenantId", command.tenantId)

            bindings.forEach { (key, value) -> query.bind(key, value) }

            query.mapTo<DocumentTemplate>()
                .findOne()
                .orElse(null)
        }
    }

    private fun getExisting(tenantId: Long, id: Long): DocumentTemplate? = jdbi.withHandle<DocumentTemplate?, Exception> { handle ->
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

package app.epistola.suite.templates.commands

import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.DocumentTemplate
import app.epistola.suite.templates.model.DataExample
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.templates.validation.DataModelValidationException
import app.epistola.suite.templates.validation.JsonSchemaValidator
import app.epistola.suite.templates.validation.ValidationError
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * Updates a template's metadata (name, dataModel, dataExamples).
 * Note: templateModel is now stored in TemplateVersion and updated via version commands.
 *
 * @property forceUpdate When true, validation warnings don't block the update.
 *                       Warnings are returned in the result instead of throwing.
 */
data class UpdateDocumentTemplate(
    val id: TemplateId,
    val name: String? = null,
    val themeId: ThemeKey? = null,
    val themeCatalogKey: app.epistola.suite.common.ids.CatalogKey? = null,
    val clearThemeId: Boolean = false,
    val dataModel: ObjectNode? = null,
    val dataExamples: List<DataExample>? = null,
    val pdfaEnabled: Boolean? = null,
    val forceUpdate: Boolean = false,
) : Command<UpdateDocumentTemplateResult?>,
    RequiresPermission {
    override val permission = Permission.TEMPLATE_EDIT
    override val tenantKey: TenantKey get() = id.tenantKey
}

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
        requireCatalogEditable(command.id.tenantKey, command.id.catalogKey)

        // Validate property names in the schema
        if (command.dataModel != null) {
            val invalidNames = jsonSchemaValidator.validatePropertyNames(command.dataModel)
            if (invalidNames.isNotEmpty()) {
                throw DataModelValidationException(
                    mapOf("_propertyNames" to invalidNames.map { ValidationError("Invalid property name at $it: must contain only letters, digits, and underscores", it) }),
                )
            }
        }

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
            val existing = getExisting(command.id) ?: return null
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
            val existing = getExisting(command.id) ?: return null
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
        if (command.clearThemeId) {
            updates.add("theme_key = NULL")
            updates.add("theme_catalog_key = NULL")
        } else if (command.themeId != null) {
            updates.add("theme_key = :themeId")
            updates.add("theme_catalog_key = :themeCatalogKey")
            bindings["themeId"] = command.themeId
            bindings["themeCatalogKey"] = command.themeCatalogKey
        }
        if (command.dataModel != null) {
            updates.add("data_model = :dataModel::jsonb")
            bindings["dataModel"] = objectMapper.writeValueAsString(command.dataModel)
        }
        if (command.dataExamples != null) {
            updates.add("data_examples = :dataExamples::jsonb")
            bindings["dataExamples"] = objectMapper.writeValueAsString(command.dataExamples)
        }
        if (command.pdfaEnabled != null) {
            updates.add("pdfa_enabled = :pdfaEnabled")
            bindings["pdfaEnabled"] = command.pdfaEnabled
        }

        if (updates.isEmpty()) {
            val existing = getExisting(command.id) ?: return null
            return UpdateDocumentTemplateResult(template = existing, warnings = warnings)
        }

        updates.add("last_modified = NOW()")

        val sql = """
            UPDATE document_templates
            SET ${updates.joinToString(", ")}
            WHERE id = :id AND tenant_key = :tenantId AND catalog_key = :catalogKey
        """

        val rowsUpdated = jdbi.withHandle<Int, Exception> { handle ->
            val update = handle.createUpdate(sql)
                .bind("id", command.id.key)
                .bind("tenantId", command.id.tenantKey)
                .bind("catalogKey", command.id.catalogKey)

            bindings.forEach { (key, value) -> update.bind(key, value) }

            update.execute()
        }

        if (rowsUpdated == 0) return null

        // Refetch with full context (catalog_type via JOIN)
        val updated = GetDocumentTemplate(id = command.id).query() ?: return null

        return UpdateDocumentTemplateResult(template = updated, warnings = warnings)
    }

    private fun getExisting(id: TemplateId): DocumentTemplate? = GetDocumentTemplate(id = id).query()
}

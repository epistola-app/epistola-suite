package app.epistola.suite.templates.commands

import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.model.ContractVersion
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
 * Updates a single data example within a template's draft contract version.
 * Only validates the single example being updated against the schema.
 */
data class UpdateDataExample(
    val templateId: TemplateId,
    val exampleId: String,
    val name: String? = null,
    val data: ObjectNode? = null,
    val forceUpdate: Boolean = false,
) : Command<UpdateDataExampleResult?>,
    RequiresPermission {
    override val permission = Permission.TEMPLATE_EDIT
    override val tenantKey: TenantKey get() = templateId.tenantKey
}

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
        requireCatalogEditable(command.templateId.tenantKey, command.templateId.catalogKey)

        return jdbi.inTransaction<UpdateDataExampleResult?, Exception> { handle ->
            // Load and lock the draft contract version
            val draftContract = handle.createQuery(
                """
                SELECT *
                FROM contract_versions
                WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
                  AND template_key = :templateKey AND status = 'draft'
                FOR UPDATE
                """,
            )
                .bind("tenantKey", command.templateId.tenantKey)
                .bind("catalogKey", command.templateId.catalogKey)
                .bind("templateKey", command.templateId.key)
                .mapTo<ContractVersion>()
                .findOne()
                .orElse(null) ?: return@inTransaction null

            val existingExample = draftContract.dataExamples.find { it.id == command.exampleId }
                ?: return@inTransaction null

            val updatedExample = DataExample(
                id = existingExample.id,
                name = command.name ?: existingExample.name,
                data = command.data ?: existingExample.data,
            )

            // Validate only the updated example against schema
            val warnings = mutableMapOf<String, List<ValidationError>>()
            val schema = draftContract.dataModel

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

            val updatedExamples = draftContract.dataExamples.map { example ->
                if (example.id == command.exampleId) updatedExample else example
            }

            handle.createUpdate(
                """
                UPDATE contract_versions
                SET data_examples = :dataExamples::jsonb
                WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
                  AND template_key = :templateKey AND status = 'draft'
                """,
            )
                .bind("tenantKey", command.templateId.tenantKey)
                .bind("catalogKey", command.templateId.catalogKey)
                .bind("templateKey", command.templateId.key)
                .bind("dataExamples", objectMapper.writeValueAsString(updatedExamples))
                .execute()

            UpdateDataExampleResult(example = updatedExample, warnings = warnings)
        }
    }
}

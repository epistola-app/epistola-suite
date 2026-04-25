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
 *
 * @property forceUpdate When true, validation warnings don't block the update.
 *                       Warnings are returned in the result instead of throwing.
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
        requireCatalogEditable(command.templateId.tenantKey, command.templateId.catalogKey)

        val draftContract = getDraftContractVersion(command.templateId) ?: return null

        // Find the example to update
        val existingExample = draftContract.dataExamples.find { it.id == command.exampleId }
            ?: return null

        // Build updated example
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

        // Replace the example in the list
        val updatedExamples = draftContract.dataExamples.map { example ->
            if (example.id == command.exampleId) updatedExample else example
        }

        // Persist to contract_versions
        updateContractDataExamples(command.templateId, updatedExamples)

        return UpdateDataExampleResult(example = updatedExample, warnings = warnings)
    }

    private fun getDraftContractVersion(templateId: TemplateId): ContractVersion? = jdbi.withHandle<ContractVersion?, Exception> { handle ->
        handle.createQuery(
            """
                SELECT *
                FROM contract_versions
                WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
                  AND template_key = :templateKey AND status = 'draft'
                """,
        )
            .bind("tenantKey", templateId.tenantKey)
            .bind("catalogKey", templateId.catalogKey)
            .bind("templateKey", templateId.key)
            .mapTo<ContractVersion>()
            .findOne()
            .orElse(null)
    }

    private fun updateContractDataExamples(
        templateId: TemplateId,
        dataExamples: List<DataExample>,
    ) {
        jdbi.withHandle<Unit, Exception> { handle ->
            handle.createUpdate(
                """
                UPDATE contract_versions
                SET data_examples = :dataExamples::jsonb
                WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
                  AND template_key = :templateKey AND status = 'draft'
                """,
            )
                .bind("tenantKey", templateId.tenantKey)
                .bind("catalogKey", templateId.catalogKey)
                .bind("templateKey", templateId.key)
                .bind("dataExamples", objectMapper.writeValueAsString(dataExamples))
                .execute()
        }
    }
}

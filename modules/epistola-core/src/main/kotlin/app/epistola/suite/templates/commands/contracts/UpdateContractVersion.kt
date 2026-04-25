package app.epistola.suite.templates.commands.contracts

import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.model.ContractVersion
import app.epistola.suite.templates.model.DataExample
import app.epistola.suite.templates.validation.JsonSchemaValidator
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * Updates the draft contract version's schema, data model, and/or data examples.
 * Returns null if no draft contract version exists for the template.
 */
data class UpdateContractVersion(
    val templateId: TemplateId,
    val schema: ObjectNode? = null,
    val dataModel: ObjectNode? = null,
    val dataExamples: List<DataExample>? = null,
    val forceUpdate: Boolean = false,
) : Command<UpdateContractVersionResult?>,
    RequiresPermission {
    override val permission = Permission.TEMPLATE_EDIT
    override val tenantKey: TenantKey get() = templateId.tenantKey
}

data class UpdateContractVersionResult(
    val contractVersion: ContractVersion,
    val warnings: Map<String, List<String>> = emptyMap(),
)

@Component
class UpdateContractVersionHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
    private val jsonSchemaValidator: JsonSchemaValidator,
) : CommandHandler<UpdateContractVersion, UpdateContractVersionResult?> {
    override fun handle(command: UpdateContractVersion): UpdateContractVersionResult? {
        requireCatalogEditable(command.templateId.tenantKey, command.templateId.catalogKey)
        return jdbi.inTransaction<UpdateContractVersionResult?, Exception> { handle ->
            // Load the draft contract version
            val draft = handle.createQuery(
                """
                SELECT id, tenant_key, catalog_key, template_key, schema, data_model, data_examples,
                       status, created_at, published_at
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

            // Build dynamic UPDATE
            val updates = mutableListOf<String>()
            val bindings = mutableMapOf<String, Any?>()

            val effectiveDataModel = command.dataModel ?: draft.dataModel

            if (command.dataModel != null) {
                // Validate property names
                val invalidNames = jsonSchemaValidator.validatePropertyNames(command.dataModel)
                if (invalidNames.isNotEmpty()) {
                    error("Invalid property names in schema: ${invalidNames.joinToString()}")
                }
                updates.add("data_model = :dataModel::jsonb")
                bindings["dataModel"] = objectMapper.writeValueAsString(command.dataModel)
            }

            if (command.schema != null) {
                updates.add("schema = :schema::jsonb")
                bindings["schema"] = objectMapper.writeValueAsString(command.schema)
            }

            // Validate examples against schema
            val effectiveExamples = command.dataExamples ?: draft.dataExamples.toList()
            val warnings = mutableMapOf<String, List<String>>()

            if (effectiveDataModel != null && effectiveExamples.isNotEmpty()) {
                val validationErrors = jsonSchemaValidator.validateExamples(effectiveDataModel, effectiveExamples)
                if (validationErrors.isNotEmpty()) {
                    if (!command.forceUpdate) {
                        error("Data examples are incompatible with the schema: ${validationErrors.entries.joinToString { "${it.key}: ${it.value.joinToString { e -> e.message }}" }}")
                    }
                    warnings.putAll(validationErrors.mapValues { (_, errors) -> errors.map { it.message } })
                }
            }

            if (command.dataExamples != null) {
                updates.add("data_examples = :dataExamples::jsonb")
                bindings["dataExamples"] = objectMapper.writeValueAsString(command.dataExamples)
            }

            if (updates.isEmpty()) {
                return@inTransaction UpdateContractVersionResult(contractVersion = draft)
            }

            val sql = """
                UPDATE contract_versions
                SET ${updates.joinToString(", ")}
                WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
                  AND template_key = :templateKey AND status = 'draft'
                RETURNING id, tenant_key, catalog_key, template_key, schema, data_model, data_examples, status, created_at, published_at
            """

            val updated = handle.createQuery(sql)
                .bind("tenantKey", command.templateId.tenantKey)
                .bind("catalogKey", command.templateId.catalogKey)
                .bind("templateKey", command.templateId.key)
                .also { query -> bindings.forEach { (key, value) -> query.bind(key, value) } }
                .mapTo<ContractVersion>()
                .one()

            UpdateContractVersionResult(contractVersion = updated, warnings = warnings)
        }
    }
}

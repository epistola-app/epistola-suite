// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.contracts.commands

import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.catalog.versions.CatalogVersionRef
import app.epistola.suite.catalog.versions.CatalogVersionScoped
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.TemplateNotFoundException
import app.epistola.suite.templates.contracts.queries.readDataContract
import app.epistola.suite.templates.model.DataContract
import app.epistola.suite.templates.model.DataExample
import app.epistola.suite.templates.templateAtAddress
import app.epistola.suite.templates.validation.DataModelValidationException
import app.epistola.suite.templates.validation.JsonSchemaValidator
import app.epistola.suite.templates.validation.requireAtLeastOneDataExample
import app.epistola.suite.templates.validation.requireValidDataContractSchema
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * Updates a template's data contract: its schema ([dataModel], or none with [clearDataModel]) and its
 * examples. What is not given is kept. The examples must satisfy the schema; with [forceUpdate] the
 * update goes through and the failures come back as warnings. An edit makes the variants' ready
 * marks stale, since a variant's readiness covers its contract.
 */
data class UpdateDataContract(
    val templateId: TemplateId,
    val dataModel: ObjectNode? = null,
    val dataExamples: List<DataExample>? = null,
    val clearDataModel: Boolean = false,
    val forceUpdate: Boolean = false,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogVersionRef? = null,
) : CatalogVersionScoped,
    Command<UpdateDataContractResult>,
    RequiresPermission {
    override val permission = Permission.TEMPLATE_EDIT
    override val tenantKey: TenantKey get() = templateId.tenantKey
}

data class UpdateDataContractResult(
    val contract: DataContract,
    /** Example name -> what the schema says is wrong with it; set only with `forceUpdate`. */
    val warnings: Map<String, List<String>> = emptyMap(),
)

@Component
class UpdateDataContractHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
    private val jsonSchemaValidator: JsonSchemaValidator,
) : CommandHandler<UpdateDataContract, UpdateDataContractResult> {
    override fun handle(command: UpdateDataContract): UpdateDataContractResult {
        val template = command.templateId
        requireCatalogEditable(template.tenantKey, template.catalogKey)
        return jdbi.inTransaction<UpdateDataContractResult, Exception> { handle ->
            val current = readDataContract(handle, objectMapper, template, forUpdate = true)
                ?: throw TemplateNotFoundException(template.tenantKey, template.key)

            command.dataModel?.let { model ->
                jsonSchemaValidator.requireValidDataContractSchema(model)
                val invalidNames = jsonSchemaValidator.validatePropertyNames(model)
                if (invalidNames.isNotEmpty()) error("Invalid property names in schema: ${invalidNames.joinToString()}")
            }
            val dataModel = if (command.clearDataModel) null else command.dataModel ?: current.dataModel
            val examples = command.dataExamples ?: current.dataExamples
            requireAtLeastOneDataExample(examples)

            val warnings = mutableMapOf<String, List<String>>()
            if (dataModel != null && examples.isNotEmpty()) {
                val errors = jsonSchemaValidator.validateExamples(dataModel, examples)
                if (errors.isNotEmpty()) {
                    if (!command.forceUpdate) throw DataModelValidationException(errors)
                    warnings.putAll(errors.mapValues { (_, list) -> list.map { it.message } })
                }
            }

            handle.createUpdate(
                """
                UPDATE document_templates
                SET data_model = CAST(:dataModel AS JSONB), data_examples = CAST(:dataExamples AS JSONB), updated_at = NOW()
                WHERE tenant_key = :t AND version_id = ${versionAt("t", "c")} AND resource_id = ${templateAtAddress("t", "c", "k")}
                """,
            )
                .bind("t", template.tenantKey)
                .bind("c", template.catalogKey)
                .bind("k", template.key)
                .bind("dataModel", dataModel?.let(objectMapper::writeValueAsString))
                .bind("dataExamples", objectMapper.writeValueAsString(examples))
                .execute()

            UpdateDataContractResult(DataContract(dataModel, examples), warnings)
        }
    }
}

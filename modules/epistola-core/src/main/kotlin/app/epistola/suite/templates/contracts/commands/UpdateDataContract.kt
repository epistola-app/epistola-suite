// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.contracts.commands

import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.common.Revisions
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.TemplateNotFoundException
import app.epistola.suite.templates.TemplateStore
import app.epistola.suite.templates.commands.templateRevision
import app.epistola.suite.templates.contracts.queries.readDataContract
import app.epistola.suite.templates.model.DataContract
import app.epistola.suite.templates.model.DataExample
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
    override val catalogVersion: CatalogReleaseRef? = null,
    /** The template's revision the change was made against; null to write whatever it is at now. */
    val expectedRevision: Long? = null,
) : CatalogReleaseScoped,
    Command<UpdateDataContractResult>,
    RequiresPermission {
    override val permission = Permission.TEMPLATE_EDIT
    override val tenantKey: TenantKey get() = templateId.tenantKey
}

data class UpdateDataContractResult(
    val contract: DataContract,
    /** Example name -> what the schema says is wrong with it; set only with `forceUpdate`. */
    val warnings: Map<String, List<String>> = emptyMap(),
    /** The template's revision after the write. */
    val revision: Long = 1,
)

@Component
class UpdateDataContractHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
    private val jsonSchemaValidator: JsonSchemaValidator,
    private val templates: TemplateStore,
) : CommandHandler<UpdateDataContract, UpdateDataContractResult> {
    override fun handle(command: UpdateDataContract): UpdateDataContractResult {
        val template = command.templateId
        requireCatalogEditable(template.catalogId)
        return jdbi.inTransaction<UpdateDataContractResult, Exception> { handle ->
            val current = readDataContract(handle, objectMapper, template, forUpdate = true)
                ?: throw TemplateNotFoundException(template.tenantKey, template.key)
            // The row is locked now, so its revision holds until this transaction ends.
            Revisions.refuse("Template '${template.key.value}'", command.expectedRevision, templateRevision(handle, template))

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

            val revision = writeContract(handle, templates, objectMapper, template, dataModel, examples)

            // As stored: each example by its key, which is its id from now on.
            val stored = TemplateStore.examplesOf(TemplateStore.entriesOf(examples, objectMapper), objectMapper)
            UpdateDataContractResult(DataContract(dataModel, stored, revision), warnings, revision)
        }
    }
}

/**
 * Saves [template]'s data contract: [dataModel] and [examples], each example by its key (derived
 * when it has none) as the wire form keeps it. The template's revision after the write.
 */
internal fun writeContract(
    handle: org.jdbi.v3.core.Handle,
    templates: TemplateStore,
    objectMapper: ObjectMapper,
    template: app.epistola.suite.common.ids.TemplateId,
    dataModel: tools.jackson.databind.node.ObjectNode?,
    examples: List<app.epistola.suite.templates.model.DataExample>,
): Long = checkNotNull(
    templates.modify(handle, template, app.epistola.suite.security.currentUserIdOrNull()?.value, null, "Template '${template.key.value}'") { stored ->
        stored.copy(
            dataModel = dataModel?.let { objectMapper.convertValue(it, object : tools.jackson.core.type.TypeReference<Map<String, Any?>>() {}) },
            dataExamples = TemplateStore.entriesOf(examples, objectMapper),
        )
    },
) { "Template ${template.key.value} was locked" }.second.revision

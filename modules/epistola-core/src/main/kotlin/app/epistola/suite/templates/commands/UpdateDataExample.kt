// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.commands

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
import app.epistola.suite.templates.TemplateStore
import app.epistola.suite.templates.contracts.commands.writeContract
import app.epistola.suite.templates.contracts.queries.readDataContract
import app.epistola.suite.templates.model.DataExample
import app.epistola.suite.templates.validation.DataModelValidationException
import app.epistola.suite.templates.validation.JsonSchemaValidator
import app.epistola.suite.templates.validation.ValidationError
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * Updates a single data example within a template's data contract.
 * Only validates the single example being updated against the schema.
 */
data class UpdateDataExample(
    val templateId: TemplateId,
    val exampleId: String,
    val name: String? = null,
    val data: ObjectNode? = null,
    val forceUpdate: Boolean = false,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogReleaseRef? = null,
    /** The template's revision the change was made against; null to write whatever it is at now. */
    val expectedRevision: Long? = null,
) : CatalogReleaseScoped,
    Command<UpdateDataExampleResult?>,
    RequiresPermission {
    override val permission = Permission.TEMPLATE_EDIT
    override val tenantKey: TenantKey get() = templateId.tenantKey
}

data class UpdateDataExampleResult(
    val example: DataExample,
    val warnings: Map<String, List<ValidationError>> = emptyMap(),
    /** The template's revision after the write. */
    val revision: Long = 1,
)

@Component
class UpdateDataExampleHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
    private val jsonSchemaValidator: JsonSchemaValidator,
    private val templates: TemplateStore,
) : CommandHandler<UpdateDataExample, UpdateDataExampleResult?> {
    override fun handle(command: UpdateDataExample): UpdateDataExampleResult? {
        requireCatalogEditable(command.templateId.catalogId)

        return jdbi.inTransaction<UpdateDataExampleResult?, Exception> { handle ->
            // Load and lock the draft contract version
            val draftContract = readDataContract(handle, objectMapper, command.templateId, forUpdate = true) ?: return@inTransaction null
            // The row is locked now, so its revision holds until this transaction ends.
            Revisions.refuse("Template '${command.templateId.key.value}'", command.expectedRevision, templateRevision(handle, command.templateId))

            val existingExample = draftContract.dataExamples.find { it.id == command.exampleId }
                ?: return@inTransaction null

            val updatedExample = DataExample(
                id = existingExample.id,
                name = command.name ?: existingExample.name,
                data = command.data ?: existingExample.data,
                // A rename keeps the address.
                key = existingExample.key,
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

            val revision = writeContract(handle, templates, objectMapper, command.templateId, schema, updatedExamples)

            UpdateDataExampleResult(example = updatedExample, warnings = warnings, revision = revision)
        }
    }
}

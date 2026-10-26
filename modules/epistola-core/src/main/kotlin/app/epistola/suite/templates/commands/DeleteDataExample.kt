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
import app.epistola.suite.templates.validation.requireAtLeastOneDataExample
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Deletes a single data example from a template's draft contract version.
 */
data class DeleteDataExample(
    val templateId: TemplateId,
    val exampleId: String,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogReleaseRef? = null,
    /** The template's revision the change was made against; null to write whatever it is at now. */
    val expectedRevision: Long? = null,
) : CatalogReleaseScoped,
    Command<DeleteDataExampleResult?>,
    RequiresPermission {
    override val permission = Permission.TEMPLATE_EDIT
    override val tenantKey: TenantKey get() = templateId.tenantKey
}

data class DeleteDataExampleResult(
    val deleted: Boolean,
    /** The template's revision after the write; null when nothing was deleted. */
    val revision: Long? = null,
)

@Component
class DeleteDataExampleHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
    private val templates: TemplateStore,
) : CommandHandler<DeleteDataExample, DeleteDataExampleResult?> {
    override fun handle(command: DeleteDataExample): DeleteDataExampleResult? {
        requireCatalogEditable(command.templateId.catalogId)

        return jdbi.inTransaction<DeleteDataExampleResult?, Exception> { handle ->
            // Load and lock the draft contract version
            val draftContract = readDataContract(handle, objectMapper, command.templateId, forUpdate = true) ?: return@inTransaction null
            // The row is locked now, so its revision holds until this transaction ends.
            Revisions.refuse("Template '${command.templateId.key.value}'", command.expectedRevision, templateRevision(handle, command.templateId))

            val exampleExists = draftContract.dataExamples.any { it.id == command.exampleId }
            if (!exampleExists) {
                return@inTransaction DeleteDataExampleResult(deleted = false)
            }

            val updatedExamples = draftContract.dataExamples.filter { it.id != command.exampleId }
            requireAtLeastOneDataExample(updatedExamples)

            val revision = writeContract(handle, templates, objectMapper, command.templateId, draftContract.dataModel, updatedExamples)

            DeleteDataExampleResult(deleted = true, revision = revision)
        }
    }
}

// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.commands

import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.catalog.versions.CatalogVersionRef
import app.epistola.suite.catalog.versions.CatalogVersionScoped
import app.epistola.suite.common.Revisions
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.contracts.queries.readDataContract
import app.epistola.suite.templates.templateAtAddress
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
    override val catalogVersion: CatalogVersionRef? = null,
    /** The template's revision the change was made against; null to write whatever it is at now. */
    val expectedRevision: Long? = null,
) : CatalogVersionScoped,
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
) : CommandHandler<DeleteDataExample, DeleteDataExampleResult?> {
    override fun handle(command: DeleteDataExample): DeleteDataExampleResult? {
        requireCatalogEditable(command.templateId.tenantKey, command.templateId.catalogKey)

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

            val revision = handle.createQuery(
                """
                UPDATE document_templates
                SET data_examples = :dataExamples::jsonb, updated_at = NOW()
                WHERE tenant_key = :tenantKey AND version_id = ${versionAt("tenantKey", "catalogKey")}
                  AND resource_id = ${templateAtAddress("tenantKey", "catalogKey", "templateKey")}
                RETURNING revision
                """,
            )
                .bind("tenantKey", command.templateId.tenantKey)
                .bind("catalogKey", command.templateId.catalogKey)
                .bind("templateKey", command.templateId.key)
                .bind("dataExamples", objectMapper.writeValueAsString(updatedExamples))
                .mapTo(Long::class.java)
                .one()

            DeleteDataExampleResult(deleted = true, revision = revision)
        }
    }
}

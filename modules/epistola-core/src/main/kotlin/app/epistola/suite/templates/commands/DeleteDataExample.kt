package app.epistola.suite.templates.commands

import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.contracts.model.ContractVersion
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Deletes a single data example from a template's draft contract version.
 */
data class DeleteDataExample(
    val templateId: TemplateId,
    val exampleId: String,
) : Command<DeleteDataExampleResult?>,
    RequiresPermission {
    override val permission = Permission.TEMPLATE_EDIT
    override val tenantKey: TenantKey get() = templateId.tenantKey
}

data class DeleteDataExampleResult(
    val deleted: Boolean,
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

            val exampleExists = draftContract.dataExamples.any { it.id == command.exampleId }
            if (!exampleExists) {
                return@inTransaction DeleteDataExampleResult(deleted = false)
            }

            val updatedExamples = draftContract.dataExamples.filter { it.id != command.exampleId }

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

            DeleteDataExampleResult(deleted = true)
        }
    }
}

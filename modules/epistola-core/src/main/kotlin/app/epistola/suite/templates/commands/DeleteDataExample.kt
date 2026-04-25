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

/**
 * Result of deleting a data example.
 *
 * @property deleted True if the example was found and deleted
 */
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

        val draftContract = getDraftContractVersion(command.templateId) ?: return null

        // Check if example exists
        val exampleExists = draftContract.dataExamples.any { it.id == command.exampleId }
        if (!exampleExists) {
            return DeleteDataExampleResult(deleted = false)
        }

        // Remove the example from the list
        val updatedExamples = draftContract.dataExamples.filter { it.id != command.exampleId }

        // Persist to contract_versions
        updateContractDataExamples(command.templateId, updatedExamples)

        return DeleteDataExampleResult(deleted = true)
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

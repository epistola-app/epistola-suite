package app.epistola.suite.templates.commands

import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.DocumentTemplate
import app.epistola.suite.templates.model.DataExample
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Deletes a single data example from a template.
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

        val existing = getExisting(command.templateId) ?: return null

        // Check if example exists
        val exampleExists = existing.dataExamples.any { it.id == command.exampleId }
        if (!exampleExists) {
            return DeleteDataExampleResult(deleted = false)
        }

        // Remove the example from the list
        val updatedExamples = existing.dataExamples.filter { it.id != command.exampleId }

        // Persist
        updateDataExamples(command.templateId, updatedExamples)
            ?: return null

        return DeleteDataExampleResult(deleted = true)
    }

    private fun getExisting(templateId: TemplateId): DocumentTemplate? = jdbi.withHandle<DocumentTemplate?, Exception> { handle ->
        handle.createQuery(
            """
                SELECT id, tenant_key, name, data_model, data_examples, created_at, last_modified
                FROM document_templates
                WHERE id = :id AND tenant_key = :tenantId
                """,
        )
            .bind("id", templateId.key)
            .bind("tenantId", templateId.tenantKey)
            .mapTo<DocumentTemplate>()
            .findOne()
            .orElse(null)
    }

    private fun updateDataExamples(
        templateId: TemplateId,
        dataExamples: List<DataExample>,
    ): DocumentTemplate? = jdbi.withHandle<DocumentTemplate?, Exception> { handle ->
        handle.createQuery(
            """
            UPDATE document_templates
            SET data_examples = :dataExamples::jsonb, last_modified = NOW()
            WHERE id = :id AND tenant_key = :tenantId
            RETURNING id, tenant_key, name, data_model, data_examples, created_at, last_modified
            """,
        )
            .bind("id", templateId.key)
            .bind("tenantId", templateId.tenantKey)
            .bind("dataExamples", objectMapper.writeValueAsString(dataExamples))
            .mapTo<DocumentTemplate>()
            .findOne()
            .orElse(null)
    }
}

package app.epistola.suite.templates.commands

import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
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
    val tenantId: Long,
    val templateId: Long,
    val exampleId: String,
) : Command<DeleteDataExampleResult?>

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
        val existing = getExisting(command.tenantId, command.templateId) ?: return null

        // Check if example exists
        val exampleExists = existing.dataExamples.any { it.id == command.exampleId }
        if (!exampleExists) {
            return DeleteDataExampleResult(deleted = false)
        }

        // Remove the example from the list
        val updatedExamples = existing.dataExamples.filter { it.id != command.exampleId }

        // Persist
        updateDataExamples(command.tenantId, command.templateId, updatedExamples)
            ?: return null

        return DeleteDataExampleResult(deleted = true)
    }

    private fun getExisting(tenantId: Long, templateId: Long): DocumentTemplate? = jdbi.withHandle<DocumentTemplate?, Exception> { handle ->
        handle.createQuery(
            """
                SELECT id, tenant_id, name, data_model, data_examples, created_at, last_modified
                FROM document_templates
                WHERE id = :id AND tenant_id = :tenantId
                """,
        )
            .bind("id", templateId)
            .bind("tenantId", tenantId)
            .mapTo<DocumentTemplate>()
            .findOne()
            .orElse(null)
    }

    private fun updateDataExamples(
        tenantId: Long,
        templateId: Long,
        dataExamples: List<DataExample>,
    ): DocumentTemplate? = jdbi.withHandle<DocumentTemplate?, Exception> { handle ->
        handle.createQuery(
            """
            UPDATE document_templates
            SET data_examples = :dataExamples::jsonb, last_modified = NOW()
            WHERE id = :id AND tenant_id = :tenantId
            RETURNING id, tenant_id, name, data_model, data_examples, created_at, last_modified
            """,
        )
            .bind("id", templateId)
            .bind("tenantId", tenantId)
            .bind("dataExamples", objectMapper.writeValueAsString(dataExamples))
            .mapTo<DocumentTemplate>()
            .findOne()
            .orElse(null)
    }
}

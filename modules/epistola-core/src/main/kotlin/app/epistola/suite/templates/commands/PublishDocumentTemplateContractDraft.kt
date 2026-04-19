package app.epistola.suite.templates.commands

import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.DocumentTemplate
import app.epistola.suite.templates.validation.JsonSchemaValidator
import app.epistola.suite.templates.validation.RecentUsageCompatibilityResult
import app.epistola.suite.templates.validation.TemplateRecentUsageCompatibilityService
import app.epistola.suite.templates.validation.ValidationError
import app.epistola.suite.validation.ValidationException
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

data class PublishDocumentTemplateContractDraft(
    val id: TemplateId,
    val forceUpdate: Boolean = false,
) : Command<PublishDocumentTemplateContractDraftResult?>,
    RequiresPermission {
    override val permission = Permission.TEMPLATE_EDIT
    override val tenantKey: TenantKey get() = id.tenantKey
}

data class PublishDocumentTemplateContractDraftResult(
    val template: DocumentTemplate,
)

class PublishDocumentTemplateContractValidationException(
    val validationErrors: Map<String, List<ValidationError>>,
    val recentUsage: RecentUsageCompatibilityResult?,
) : RuntimeException("Draft contract publish validation failed")

@Component
class PublishDocumentTemplateContractDraftHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
    private val jsonSchemaValidator: JsonSchemaValidator,
    private val recentUsageCompatibilityService: TemplateRecentUsageCompatibilityService,
) : CommandHandler<PublishDocumentTemplateContractDraft, PublishDocumentTemplateContractDraftResult?> {
    override fun handle(command: PublishDocumentTemplateContractDraft): PublishDocumentTemplateContractDraftResult? {
        val existing = getExisting(command.id) ?: return null
        if (!existing.hasDraftContract) {
            throw ValidationException("draftContract", "No draft data contract is available to publish.")
        }

        val schemaToPublish = existing.dataModel
        val examplesToPublish = existing.dataExamples
        val blockingErrors = mutableMapOf<String, MutableList<ValidationError>>()
        var recentUsageResult: RecentUsageCompatibilityResult? = null

        if (schemaToPublish != null) {
            if (examplesToPublish.isNotEmpty()) {
                mergeValidationErrors(
                    blockingErrors,
                    jsonSchemaValidator.validateExamples(schemaToPublish, examplesToPublish),
                )
            }

            recentUsageResult = recentUsageCompatibilityService.analyze(
                tenantKey = command.id.tenantKey,
                templateKey = command.id.key,
                schema = schemaToPublish,
            )
            val recentUsageErrors = mapRecentUsageErrors(recentUsageResult)
            if (command.forceUpdate) {
                // Recent usage compatibility is still surfaced to the user before
                // they override publish, but it no longer hard-blocks the publish.
            } else {
                mergeValidationErrors(blockingErrors, recentUsageErrors)
            }
        }

        if (blockingErrors.isNotEmpty()) {
            throw PublishDocumentTemplateContractValidationException(
                validationErrors = blockingErrors.mapValues { (_, value) -> value.toList() },
                recentUsage = recentUsageResult,
            )
        }

        val updated = jdbi.withHandle<DocumentTemplate?, Exception> { handle ->
            val update = if (schemaToPublish != null) {
                handle.createQuery(
                    """
                    UPDATE document_templates
                    SET data_model = :dataModel::jsonb,
                        data_examples = :dataExamples::jsonb,
                        draft_data_model = NULL,
                        draft_data_examples = NULL,
                        last_modified = NOW()
                    WHERE id = :id AND tenant_key = :tenantId
                    RETURNING id, tenant_key, name, theme_key, schema,
                              data_model AS published_data_model,
                              data_examples AS published_data_examples,
                              draft_data_model,
                              draft_data_examples,
                              pdfa_enabled,
                              created_at,
                              last_modified
                    """.trimIndent(),
                )
                    .bind("dataModel", objectMapper.writeValueAsString(schemaToPublish))
            } else {
                handle.createQuery(
                    """
                    UPDATE document_templates
                    SET data_model = NULL,
                        data_examples = :dataExamples::jsonb,
                        draft_data_model = NULL,
                        draft_data_examples = NULL,
                        last_modified = NOW()
                    WHERE id = :id AND tenant_key = :tenantId
                    RETURNING id, tenant_key, name, theme_key, schema,
                              data_model AS published_data_model,
                              data_examples AS published_data_examples,
                              draft_data_model,
                              draft_data_examples,
                              pdfa_enabled,
                              created_at,
                              last_modified
                    """.trimIndent(),
                )
            }

            update
                .bind("id", command.id.key)
                .bind("tenantId", command.id.tenantKey)
                .bind("dataExamples", objectMapper.writeValueAsString(examplesToPublish))
                .mapTo<DocumentTemplate>()
                .findOne()
                .orElse(null)
        } ?: return null

        return PublishDocumentTemplateContractDraftResult(template = updated)
    }

    private fun mergeValidationErrors(
        target: MutableMap<String, MutableList<ValidationError>>,
        source: Map<String, List<ValidationError>>,
    ) {
        source
            .filterValues { it.isNotEmpty() }
            .forEach { (key, errors) ->
                target.getOrPut(key) { mutableListOf() }.addAll(errors)
            }
    }

    private fun mapRecentUsageErrors(result: RecentUsageCompatibilityResult): Map<String, List<ValidationError>> {
        if (!result.available) {
            return mapOf(
                "recent-usage-check" to listOf(
                    ValidationError(
                        message = result.unavailableReason
                            ?: "Recent usage compatibility check is temporarily unavailable.",
                        path = "recentUsage",
                    ),
                ),
            )
        }

        return result.issues.associate { issue ->
            "recent-request:${issue.requestId}" to issue.errors.map { error ->
                val correlationInfo = issue.correlationKey?.let { " correlation=$it" } ?: ""
                ValidationError(
                    message = "${error.message} [status=${issue.status.name}$correlationInfo]",
                    path = "request:${issue.requestId} ${error.path}".trim(),
                )
            }
        }
    }

    private fun getExisting(id: TemplateId): DocumentTemplate? = jdbi.withHandle<DocumentTemplate?, Exception> { handle ->
        handle.createQuery(
            """
            SELECT id, tenant_key, name, theme_key, schema,
                   data_model AS published_data_model,
                   data_examples AS published_data_examples,
                   draft_data_model,
                   draft_data_examples,
                   pdfa_enabled,
                   created_at,
                   last_modified
            FROM document_templates
            WHERE id = :id AND tenant_key = :tenantId
            """.trimIndent(),
        )
            .bind("id", id.key)
            .bind("tenantId", id.tenantKey)
            .mapTo<DocumentTemplate>()
            .findOne()
            .orElse(null)
    }
}

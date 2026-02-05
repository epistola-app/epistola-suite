package app.epistola.suite.documents.commands

import app.epistola.suite.common.ids.BatchId
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.GenerationRequestId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.documents.batch.GenerationRequestCreatedEvent
import app.epistola.suite.documents.model.DocumentGenerationRequest
import app.epistola.suite.documents.model.RequestStatus
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import tools.jackson.databind.node.ObjectNode

/**
 * Individual item in a batch generation request.
 */
data class BatchGenerationItem(
    val templateId: TemplateId,
    val variantId: VariantId,
    val versionId: VersionId?,
    val environmentId: EnvironmentId?,
    val data: ObjectNode,
    val filename: String?,
    val correlationId: String? = null,
) {
    init {
        require((versionId != null) xor (environmentId != null)) {
            "Exactly one of versionId or environmentId must be set"
        }
    }
}

/**
 * Exception thrown when batch validation fails due to duplicate correlationIds or filenames.
 */
class BatchValidationException(
    val duplicateCorrelationIds: List<String>,
    val duplicateFilenames: List<String>,
) : IllegalArgumentException(buildMessage(duplicateCorrelationIds, duplicateFilenames)) {
    companion object {
        private fun buildMessage(correlationIds: List<String>, filenames: List<String>): String {
            val parts = mutableListOf<String>()
            if (correlationIds.isNotEmpty()) {
                parts.add("Duplicate correlationIds: ${correlationIds.joinToString(", ")}")
            }
            if (filenames.isNotEmpty()) {
                parts.add("Duplicate filenames: ${filenames.joinToString(", ")}")
            }
            return parts.joinToString("; ")
        }
    }
}

/**
 * Command to generate multiple documents asynchronously in a batch.
 *
 * Creates N requests (one per item) grouped by a batch_id.
 *
 * @property tenantId Tenant that owns the templates
 * @property items List of items to generate
 */
data class GenerateDocumentBatch(
    val tenantId: TenantId,
    val items: List<BatchGenerationItem>,
) : Command<BatchId> {
    init {
        require(items.isNotEmpty()) { "At least one item is required" }
        validateUniqueness()
    }

    private fun validateUniqueness() {
        val duplicateCorrelationIds = items.mapNotNull { it.correlationId }
            .groupingBy { it }.eachCount().filter { it.value > 1 }.keys.toList()
        val duplicateFilenames = items.mapNotNull { it.filename }
            .groupingBy { it }.eachCount().filter { it.value > 1 }.keys.toList()

        if (duplicateCorrelationIds.isNotEmpty() || duplicateFilenames.isNotEmpty()) {
            throw BatchValidationException(duplicateCorrelationIds, duplicateFilenames)
        }
    }
}

@Component
class GenerateDocumentBatchHandler(
    private val jdbi: Jdbi,
    private val eventPublisher: ApplicationEventPublisher,
) : CommandHandler<GenerateDocumentBatch, BatchId> {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun handle(command: GenerateDocumentBatch): BatchId {
        logger.info("Generating batch of {} documents for tenant {}", command.items.size, command.tenantId)

        // Store requests to publish events after transaction
        val createdRequests = mutableListOf<GenerationRequestId>()

        val batchId = jdbi.inTransaction<BatchId, Exception> { handle ->
            // 1. Validate all templates/variants/versions/environments exist
            for ((index, item) in command.items.withIndex()) {
                val templateExists = handle.createQuery(
                    """
                    SELECT EXISTS (
                        SELECT 1
                        FROM document_templates dt
                        JOIN template_variants tv ON dt.id = tv.template_id
                        WHERE dt.id = :templateId
                          AND tv.id = :variantId
                          AND dt.tenant_id = :tenantId
                    )
                    """,
                )
                    .bind("templateId", item.templateId)
                    .bind("variantId", item.variantId)
                    .bind("tenantId", command.tenantId)
                    .mapTo<Boolean>()
                    .one()

                require(templateExists) {
                    "Item $index: Template ${item.templateId} variant ${item.variantId} not found for tenant ${command.tenantId}"
                }

                if (item.versionId != null) {
                    val versionExists = handle.createQuery(
                        """
                        SELECT EXISTS (
                            SELECT 1
                            FROM template_versions ver
                            JOIN template_variants tv ON ver.variant_id = tv.id
                            JOIN document_templates dt ON tv.template_id = dt.id
                            WHERE ver.id = :versionId
                              AND ver.variant_id = :variantId
                              AND tv.template_id = :templateId
                              AND dt.tenant_id = :tenantId
                        )
                        """,
                    )
                        .bind("versionId", item.versionId)
                        .bind("variantId", item.variantId)
                        .bind("templateId", item.templateId)
                        .bind("tenantId", command.tenantId)
                        .mapTo<Boolean>()
                        .one()

                    require(versionExists) {
                        "Item $index: Version ${item.versionId} not found for template ${item.templateId} variant ${item.variantId}"
                    }
                } else {
                    val environmentExists = handle.createQuery(
                        """
                        SELECT EXISTS (
                            SELECT 1
                            FROM environments
                            WHERE id = :environmentId
                              AND tenant_id = :tenantId
                        )
                        """,
                    )
                        .bind("environmentId", item.environmentId)
                        .bind("tenantId", command.tenantId)
                        .mapTo<Boolean>()
                        .one()

                    require(environmentExists) {
                        "Item $index: Environment ${item.environmentId} not found for tenant ${command.tenantId}"
                    }
                }
            }

            // 2. Create batch metadata
            val batchId = BatchId.generate()
            handle.createUpdate(
                """
                INSERT INTO document_generation_batches (
                    id, tenant_id, total_count, completed_count, failed_count
                )
                VALUES (:batchId, :tenantId, :totalCount, 0, 0)
                """,
            )
                .bind("batchId", batchId)
                .bind("tenantId", command.tenantId)
                .bind("totalCount", command.items.size)
                .execute()

            // 3. Create N requests (one per item) with batch_id
            val batch = handle.prepareBatch(
                """
                INSERT INTO document_generation_requests (
                    id, batch_id, tenant_id, template_id, variant_id, version_id, environment_id,
                    data, filename, correlation_id, document_id, status
                )
                VALUES (:id, :batchId, :tenantId, :templateId, :variantId, :versionId, :environmentId,
                        :data::jsonb, :filename, :correlationId, NULL, :status)
                """,
            )

            for (item in command.items) {
                val requestId = GenerationRequestId.generate()
                createdRequests.add(requestId)
                batch.bind("id", requestId)
                    .bind("batchId", batchId)
                    .bind("tenantId", command.tenantId)
                    .bind("templateId", item.templateId)
                    .bind("variantId", item.variantId)
                    .bind("versionId", item.versionId)
                    .bind("environmentId", item.environmentId)
                    .bind("data", item.data.toString())
                    .bind("filename", item.filename)
                    .bind("correlationId", item.correlationId)
                    .bind("status", RequestStatus.PENDING.name)
                    .add()
            }

            val inserted = batch.execute().sum()
            logger.info("Created batch {} with {} requests for tenant {}", batchId.value, inserted, command.tenantId)

            batchId
        }

        // Publish events AFTER transaction commits for synchronous execution in tests
        // In the flattened structure, we need to query each request and publish events for synchronous execution
        if (createdRequests.isNotEmpty()) {
            jdbi.withHandle<Unit, Exception> { handle ->
                for (requestId in createdRequests) {
                    val request = handle.createQuery(
                        """
                        SELECT id, batch_id, tenant_id, template_id, variant_id, version_id, environment_id,
                               data, filename, correlation_id, document_id, status, claimed_by, claimed_at,
                               error_message, created_at, started_at, completed_at, expires_at
                        FROM document_generation_requests
                        WHERE id = :requestId
                        """,
                    )
                        .bind("requestId", requestId)
                        .mapTo<app.epistola.suite.documents.model.DocumentGenerationRequest>()
                        .one()

                    eventPublisher.publishEvent(GenerationRequestCreatedEvent(request))
                }
            }
        }

        return batchId
    }
}

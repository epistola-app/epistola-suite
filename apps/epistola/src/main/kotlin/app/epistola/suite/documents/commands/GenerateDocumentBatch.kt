package app.epistola.suite.documents.commands

import app.epistola.suite.common.UUIDv7
import app.epistola.suite.documents.model.DocumentGenerationRequest
import app.epistola.suite.documents.model.JobType
import app.epistola.suite.documents.model.RequestStatus
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.node.ObjectNode
import java.util.UUID

/**
 * Individual item in a batch generation request.
 */
data class BatchGenerationItem(
    val templateId: UUID,
    val variantId: UUID,
    val versionId: UUID?,
    val environmentId: UUID?,
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
 * @property tenantId Tenant that owns the templates
 * @property items List of items to generate
 */
data class GenerateDocumentBatch(
    val tenantId: UUID,
    val items: List<BatchGenerationItem>,
) : Command<DocumentGenerationRequest> {
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
) : CommandHandler<GenerateDocumentBatch, DocumentGenerationRequest> {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun handle(command: GenerateDocumentBatch): DocumentGenerationRequest {
        logger.info("Generating batch of {} documents for tenant {}", command.items.size, command.tenantId)

        return jdbi.inTransaction<DocumentGenerationRequest, Exception> { handle ->
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

            // 2. Create generation request (stays in PENDING status for poller to pick up)
            val requestId = UUIDv7.generate()
            val request = handle.createQuery(
                """
                INSERT INTO document_generation_requests (
                    id, tenant_id, job_type, status, total_count
                )
                VALUES (:id, :tenantId, :jobType, :status, :totalCount)
                RETURNING id, tenant_id, job_type, status, claimed_by, claimed_at,
                          total_count, completed_count, failed_count, error_message,
                          created_at, started_at, completed_at, expires_at
                """,
            )
                .bind("id", requestId)
                .bind("tenantId", command.tenantId)
                .bind("jobType", JobType.BATCH.name)
                .bind("status", RequestStatus.PENDING.name)
                .bind("totalCount", command.items.size)
                .mapTo<DocumentGenerationRequest>()
                .one()

            // 3. Create generation items
            val batch = handle.prepareBatch(
                """
                INSERT INTO document_generation_items (
                    id, request_id, template_id, variant_id, version_id, environment_id,
                    data, filename, correlation_id, status
                )
                VALUES (:id, :requestId, :templateId, :variantId, :versionId, :environmentId,
                        :data::jsonb, :filename, :correlationId, :status)
                """,
            )

            for (item in command.items) {
                val itemId = UUIDv7.generate()
                batch.bind("id", itemId)
                    .bind("requestId", request.id)
                    .bind("templateId", item.templateId)
                    .bind("variantId", item.variantId)
                    .bind("versionId", item.versionId)
                    .bind("environmentId", item.environmentId)
                    .bind("data", item.data.toString())
                    .bind("filename", item.filename)
                    .bind("correlationId", item.correlationId)
                    .bind("status", "PENDING")
                    .add()
            }

            val inserted = batch.execute().sum()
            logger.info("Created generation request {} with {} items for tenant {}", request.id, inserted, command.tenantId)

            // Request stays in PENDING status - the JobPoller will pick it up
            request
        }
    }
}

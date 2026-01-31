package app.epistola.suite.documents.commands

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

/**
 * Individual item in a batch generation request.
 */
data class BatchGenerationItem(
    val templateId: Long,
    val variantId: Long,
    val versionId: Long?,
    val environmentId: Long?,
    val data: ObjectNode,
    val filename: String?,
) {
    init {
        require((versionId != null) xor (environmentId != null)) {
            "Exactly one of versionId or environmentId must be set"
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
    val tenantId: Long,
    val items: List<BatchGenerationItem>,
) : Command<DocumentGenerationRequest> {
    init {
        require(items.isNotEmpty()) { "At least one item is required" }
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
            val request = handle.createQuery(
                """
                INSERT INTO document_generation_requests (
                    tenant_id, job_type, status, total_count
                )
                VALUES (:tenantId, :jobType, :status, :totalCount)
                RETURNING id, tenant_id, job_type, status, claimed_by, claimed_at,
                          total_count, completed_count, failed_count, error_message,
                          created_at, started_at, completed_at, expires_at
                """,
            )
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
                    request_id, template_id, variant_id, version_id, environment_id,
                    data, filename, status
                )
                VALUES (:requestId, :templateId, :variantId, :versionId, :environmentId,
                        :data::jsonb, :filename, :status)
                """,
            )

            for (item in command.items) {
                batch.bind("requestId", request.id)
                    .bind("templateId", item.templateId)
                    .bind("variantId", item.variantId)
                    .bind("versionId", item.versionId)
                    .bind("environmentId", item.environmentId)
                    .bind("data", item.data.toString())
                    .bind("filename", item.filename)
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

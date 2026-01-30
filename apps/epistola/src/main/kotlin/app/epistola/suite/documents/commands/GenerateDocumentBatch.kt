package app.epistola.suite.documents.commands

import app.epistola.suite.documents.batch.DocumentGenerationJobConfig
import app.epistola.suite.documents.model.DocumentGenerationRequest
import app.epistola.suite.documents.model.JobType
import app.epistola.suite.documents.model.RequestStatus
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.slf4j.LoggerFactory
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.parameters.JobParametersBuilder
import org.springframework.batch.core.launch.JobLauncher
import org.springframework.stereotype.Component
import tools.jackson.databind.node.ObjectNode
import java.util.UUID

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
    private val jobLauncher: JobLauncher,
    private val documentGenerationJob: Job,
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
                    """
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
                        """
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
                        """
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

            // 2. Create generation request
            val request = handle.createQuery(
                """
                INSERT INTO document_generation_requests (
                    tenant_id, job_type, status, total_count
                )
                VALUES (:tenantId, :jobType, :status, :totalCount)
                RETURNING id, tenant_id, job_type, status, batch_job_execution_id,
                          total_count, completed_count, failed_count, error_message,
                          created_at, started_at, completed_at, expires_at
                """
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
                """
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

            // 4. Launch Spring Batch job asynchronously
            launchJob(request.id)

            request
        }
    }

    private fun launchJob(requestId: UUID) {
        try {
            val jobParameters = JobParametersBuilder()
                .addString(DocumentGenerationJobConfig.PARAM_REQUEST_ID, requestId.toString())
                .addLong("timestamp", System.currentTimeMillis()) // Make parameters unique
                .toJobParameters()

            // Launch job asynchronously
            jobLauncher.run(documentGenerationJob, jobParameters)

            logger.info("Launched batch job for request {}", requestId)
        } catch (e: Exception) {
            logger.error("Failed to launch batch job for request {}: {}", requestId, e.message, e)
            // Job launch failure - mark request as FAILED
            jdbi.useHandle<Exception> { handle ->
                handle.createUpdate(
                    """
                    UPDATE document_generation_requests
                    SET status = 'FAILED',
                        error_message = :errorMessage,
                        completed_at = NOW()
                    WHERE id = :requestId
                    """
                )
                    .bind("requestId", requestId)
                    .bind("errorMessage", "Failed to launch job: ${e.message}")
                    .execute()
            }
            throw e
        }
    }
}

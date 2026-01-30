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
 * Command to generate a single document asynchronously.
 *
 * @property tenantId Tenant that owns the template
 * @property templateId Template to use for generation
 * @property variantId Variant of the template
 * @property versionId Explicit version ID (mutually exclusive with environmentId)
 * @property environmentId Environment to determine version from (mutually exclusive with versionId)
 * @property data JSON data to populate the template
 * @property filename Optional filename for the generated document
 */
data class GenerateDocument(
    val tenantId: Long,
    val templateId: Long,
    val variantId: Long,
    val versionId: Long?,
    val environmentId: Long?,
    val data: ObjectNode,
    val filename: String?,
) : Command<DocumentGenerationRequest> {
    init {
        // Validate that exactly one of versionId or environmentId is set
        require((versionId != null) xor (environmentId != null)) {
            "Exactly one of versionId or environmentId must be set"
        }
    }
}

@Component
class GenerateDocumentHandler(
    private val jdbi: Jdbi,
    private val jobLauncher: JobLauncher,
    private val documentGenerationJob: Job,
) : CommandHandler<GenerateDocument, DocumentGenerationRequest> {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun handle(command: GenerateDocument): DocumentGenerationRequest {
        logger.info("Generating single document for tenant {} template {}", command.tenantId, command.templateId)

        return jdbi.inTransaction<DocumentGenerationRequest, Exception> { handle ->
            // 1. Verify template exists and belongs to tenant
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
                .bind("templateId", command.templateId)
                .bind("variantId", command.variantId)
                .bind("tenantId", command.tenantId)
                .mapTo<Boolean>()
                .one()

            require(templateExists) {
                "Template ${command.templateId} variant ${command.variantId} not found for tenant ${command.tenantId}"
            }

            // 2. Verify version or environment exists
            if (command.versionId != null) {
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
                    .bind("versionId", command.versionId)
                    .bind("variantId", command.variantId)
                    .bind("templateId", command.templateId)
                    .bind("tenantId", command.tenantId)
                    .mapTo<Boolean>()
                    .one()

                require(versionExists) {
                    "Version ${command.versionId} not found for template ${command.templateId} variant ${command.variantId}"
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
                    .bind("environmentId", command.environmentId)
                    .bind("tenantId", command.tenantId)
                    .mapTo<Boolean>()
                    .one()

                require(environmentExists) {
                    "Environment ${command.environmentId} not found for tenant ${command.tenantId}"
                }
            }

            // 3. Create generation request
            val request = handle.createQuery(
                """
                INSERT INTO document_generation_requests (
                    tenant_id, job_type, status, total_count
                )
                VALUES (:tenantId, :jobType, :status, 1)
                RETURNING id, tenant_id, job_type, status, batch_job_execution_id,
                          total_count, completed_count, failed_count, error_message,
                          created_at, started_at, completed_at, expires_at
                """,
            )
                .bind("tenantId", command.tenantId)
                .bind("jobType", JobType.SINGLE.name)
                .bind("status", RequestStatus.PENDING.name)
                .mapTo<DocumentGenerationRequest>()
                .one()

            // 4. Create generation item
            handle.createUpdate(
                """
                INSERT INTO document_generation_items (
                    request_id, template_id, variant_id, version_id, environment_id,
                    data, filename, status
                )
                VALUES (:requestId, :templateId, :variantId, :versionId, :environmentId,
                        :data::jsonb, :filename, :status)
                """,
            )
                .bind("requestId", request.id)
                .bind("templateId", command.templateId)
                .bind("variantId", command.variantId)
                .bind("versionId", command.versionId)
                .bind("environmentId", command.environmentId)
                .bind("data", command.data.toString())
                .bind("filename", command.filename)
                .bind("status", "PENDING")
                .execute()

            logger.info("Created generation request {} for tenant {}", request.id, command.tenantId)

            // 5. Launch Spring Batch job asynchronously
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

            // Launch job asynchronously (JobLauncher is configured as async by Spring Boot)
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
                    """,
                )
                    .bind("requestId", requestId)
                    .bind("errorMessage", "Failed to launch job: ${e.message}")
                    .execute()
            }
            throw e
        }
    }
}

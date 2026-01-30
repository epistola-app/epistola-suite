package app.epistola.suite.documents.commands

import app.epistola.suite.documents.model.RequestStatus
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.slf4j.LoggerFactory
import org.springframework.batch.core.explore.JobExplorer
import org.springframework.batch.core.launch.JobOperator
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Command to cancel a pending or in-progress generation job.
 *
 * @property tenantId Tenant that owns the job
 * @property requestId The generation request ID to cancel
 */
data class CancelGenerationJob(
    val tenantId: Long,
    val requestId: UUID,
) : Command<Boolean>

@Component
class CancelGenerationJobHandler(
    private val jdbi: Jdbi,
    private val jobOperator: JobOperator,
    private val jobExplorer: JobExplorer,
) : CommandHandler<CancelGenerationJob, Boolean> {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun handle(command: CancelGenerationJob): Boolean {
        logger.info("Cancelling generation job {} for tenant {}", command.requestId, command.tenantId)

        return jdbi.inTransaction<Boolean, Exception> { handle ->
            // 1. Verify request exists and belongs to tenant
            val requestInfo = handle.createQuery(
                """
                SELECT status, batch_job_execution_id
                FROM document_generation_requests
                WHERE id = :requestId AND tenant_id = :tenantId
                """
            )
                .bind("requestId", command.requestId)
                .bind("tenantId", command.tenantId)
                .map { rs, _ ->
                    Pair(
                        RequestStatus.valueOf(rs.getString("status")),
                        rs.getLong("batch_job_execution_id")
                    )
                }
                .findOne()
                .orElse(null)

            if (requestInfo == null) {
                logger.warn("Request {} not found for tenant {}", command.requestId, command.tenantId)
                return@inTransaction false
            }

            val (status, batchJobExecutionId) = requestInfo

            // 2. Check if request can be cancelled
            if (!status.isCancellable) {
                logger.warn("Request {} cannot be cancelled (status: {})", command.requestId, status)
                return@inTransaction false
            }

            // 3. Stop the Spring Batch job if running
            if (batchJobExecutionId != null && batchJobExecutionId > 0) {
                try {
                    val jobExecution = jobExplorer.getJobExecution(batchJobExecutionId)
                    if (jobExecution != null && jobExecution.isRunning) {
                        jobOperator.stop(batchJobExecutionId)
                        logger.info("Stopped batch job execution {}", batchJobExecutionId)
                    }
                } catch (e: Exception) {
                    logger.warn("Failed to stop batch job execution {}: {}", batchJobExecutionId, e.message)
                    // Continue with cancellation even if job stop fails
                }
            }

            // 4. Mark request as cancelled
            val updated = handle.createUpdate(
                """
                UPDATE document_generation_requests
                SET status = :status,
                    completed_at = NOW()
                WHERE id = :requestId
                  AND tenant_id = :tenantId
                  AND status IN ('PENDING', 'IN_PROGRESS')
                """
            )
                .bind("requestId", command.requestId)
                .bind("tenantId", command.tenantId)
                .bind("status", RequestStatus.CANCELLED.name)
                .execute()

            if (updated > 0) {
                // 5. Mark all pending/in-progress items as failed
                handle.createUpdate(
                    """
                    UPDATE document_generation_items
                    SET status = 'FAILED',
                        error_message = 'Job cancelled by user',
                        completed_at = NOW()
                    WHERE request_id = :requestId
                      AND status IN ('PENDING', 'IN_PROGRESS')
                    """
                )
                    .bind("requestId", command.requestId)
                    .execute()

                logger.info("Cancelled generation job {}", command.requestId)
                true
            } else {
                logger.warn("Request {} was already completed or cancelled", command.requestId)
                false
            }
        }
    }
}

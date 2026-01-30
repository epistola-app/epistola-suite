package app.epistola.suite.documents.batch

import app.epistola.suite.documents.model.RequestStatus
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobExecutionListener
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Spring Batch listener for document generation job completion.
 *
 * Updates the generation request with final status and timestamps.
 * Also sets expiration date for cleanup.
 *
 * @param jdbi JDBI instance for database access
 * @param requestId The generation request ID
 * @param retentionDays Number of days to retain completed jobs
 */
class JobCompletionListener(
    private val jdbi: Jdbi,
    private val requestId: UUID,
    private val retentionDays: Int,
) : JobExecutionListener {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun beforeJob(jobExecution: JobExecution) {
        logger.info("Starting document generation job for request: {}", requestId)

        // Update request status to IN_PROGRESS
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                UPDATE document_generation_requests
                SET status = :status,
                    started_at = :startedAt,
                    batch_job_execution_id = :jobExecutionId
                WHERE id = :requestId
                """
            )
                .bind("status", RequestStatus.IN_PROGRESS.name)
                .bind("startedAt", OffsetDateTime.now())
                .bind("jobExecutionId", jobExecution.jobId)
                .bind("requestId", requestId)
                .execute()
        }
    }

    override fun afterJob(jobExecution: JobExecution) {
        val exitStatus = jobExecution.exitStatus.exitCode
        val batchStatus = jobExecution.status

        logger.info("Document generation job completed for request: {} with status: {}", requestId, batchStatus)

        jdbi.useTransaction<Exception> { handle ->
            // Get current counts to determine final status
            val counts = handle.createQuery(
                """
                SELECT total_count, completed_count, failed_count
                FROM document_generation_requests
                WHERE id = :requestId
                """
            )
                .bind("requestId", requestId)
                .map { rs, _ ->
                    Triple(
                        rs.getInt("total_count"),
                        rs.getInt("completed_count"),
                        rs.getInt("failed_count")
                    )
                }
                .one()

            val (totalCount, completedCount, failedCount) = counts

            // Determine final status
            val finalStatus = when {
                batchStatus.name == "ABANDONED" || batchStatus.name == "STOPPED" -> RequestStatus.CANCELLED
                failedCount == totalCount -> RequestStatus.FAILED // All items failed
                completedCount + failedCount == totalCount -> RequestStatus.COMPLETED // All processed (partial or full success)
                else -> RequestStatus.FAILED // Unexpected state
            }

            // Calculate expiration date
            val expiresAt = OffsetDateTime.now().plusDays(retentionDays.toLong())

            // Update request
            handle.createUpdate(
                """
                UPDATE document_generation_requests
                SET status = :status,
                    completed_at = :completedAt,
                    expires_at = :expiresAt,
                    error_message = :errorMessage
                WHERE id = :requestId
                """
            )
                .bind("status", finalStatus.name)
                .bind("completedAt", OffsetDateTime.now())
                .bind("expiresAt", expiresAt)
                .bind("errorMessage", if (finalStatus == RequestStatus.FAILED) {
                    jobExecution.allFailureExceptions.firstOrNull()?.message
                } else {
                    null
                })
                .bind("requestId", requestId)
                .execute()

            logger.info("Request {} final status: {} ({} completed, {} failed out of {})",
                requestId, finalStatus, completedCount, failedCount, totalCount)
        }
    }
}

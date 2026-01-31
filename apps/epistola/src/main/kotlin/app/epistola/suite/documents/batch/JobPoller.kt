package app.epistola.suite.documents.batch

import app.epistola.suite.documents.model.DocumentGenerationRequest
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Scheduled poller that claims and executes pending document generation jobs.
 *
 * Uses polling with `SELECT FOR UPDATE SKIP LOCKED` for safe multi-instance distribution.
 * Jobs are executed on virtual threads to avoid blocking the scheduler.
 */
@Component
@ConditionalOnProperty(
    name = ["epistola.generation.polling.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class JobPoller(
    private val jdbi: Jdbi,
    private val jobExecutor: DocumentGenerationExecutor,
    @Value("\${epistola.generation.polling.max-concurrent-jobs:2}")
    private val maxConcurrentJobs: Int,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val instanceId = "${InetAddress.getLocalHost().hostName}-${ProcessHandle.current().pid()}"
    private val activeJobs = AtomicInteger(0)
    private val executor = Executors.newVirtualThreadPerTaskExecutor()

    /**
     * Poll for pending jobs and claim one if capacity allows.
     * Runs on a fixed delay to ensure continuous polling without overlapping.
     */
    @Scheduled(fixedDelayString = "\${epistola.generation.polling.interval-ms:5000}")
    fun poll() {
        if (activeJobs.get() >= maxConcurrentJobs) {
            logger.debug("Max concurrent jobs reached ({}), skipping poll", maxConcurrentJobs)
            return
        }

        val request = claimNextPendingRequest()
        if (request != null) {
            activeJobs.incrementAndGet()
            logger.info("Claimed request {} (active jobs: {})", request.id, activeJobs.get())

            // Execute on virtual thread, don't block the scheduler
            executor.submit {
                try {
                    jobExecutor.execute(request)
                } catch (e: Exception) {
                    logger.error("Job execution failed for request {}: {}", request.id, e.message, e)
                    markRequestFailed(request.id, e.message)
                } finally {
                    activeJobs.decrementAndGet()
                }
            }
        }
    }

    /**
     * Claim the next pending request using SELECT FOR UPDATE SKIP LOCKED.
     * This ensures only one instance can claim a request even under concurrent polling.
     */
    private fun claimNextPendingRequest(): DocumentGenerationRequest? = jdbi.inTransaction<DocumentGenerationRequest?, Exception> { handle ->
        // PostgreSQL: Use CTE to select and update atomically
        handle.createQuery(
            """
                WITH claimed AS (
                    SELECT id FROM document_generation_requests
                    WHERE status = 'PENDING'
                    ORDER BY created_at
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE document_generation_requests
                SET status = 'IN_PROGRESS',
                    claimed_by = :instanceId,
                    claimed_at = NOW(),
                    started_at = NOW()
                FROM claimed
                WHERE document_generation_requests.id = claimed.id
                RETURNING document_generation_requests.id,
                          document_generation_requests.tenant_id,
                          document_generation_requests.job_type,
                          document_generation_requests.status,
                          document_generation_requests.claimed_by,
                          document_generation_requests.claimed_at,
                          document_generation_requests.total_count,
                          document_generation_requests.completed_count,
                          document_generation_requests.failed_count,
                          document_generation_requests.error_message,
                          document_generation_requests.created_at,
                          document_generation_requests.started_at,
                          document_generation_requests.completed_at,
                          document_generation_requests.expires_at
                """,
        )
            .bind("instanceId", instanceId)
            .mapTo<DocumentGenerationRequest>()
            .findOne()
            .orElse(null)
    }

    /**
     * Mark a request as failed when job execution throws an exception.
     */
    private fun markRequestFailed(requestId: UUID, errorMessage: String?) {
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
                .bind("errorMessage", errorMessage?.take(1000))
                .execute()
        }
    }
}

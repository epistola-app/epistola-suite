package app.epistola.suite.documents.batch

import app.epistola.suite.common.ids.GenerationRequestId
import app.epistola.suite.documents.JobPollingProperties
import app.epistola.suite.documents.model.DocumentGenerationRequest
import io.micrometer.core.instrument.MeterRegistry
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Scheduled poller that claims and executes pending document generation jobs.
 *
 * Uses polling with `SELECT FOR UPDATE SKIP LOCKED` for safe multi-instance distribution.
 * Jobs are executed on virtual threads to avoid blocking the scheduler.
 *
 * Supports adaptive batch sizing: claims multiple jobs per poll cycle based on system
 * performance, measured via job processing time.
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
    private val properties: JobPollingProperties,
    private val batchSizer: AdaptiveBatchSizer,
    private val meterRegistry: MeterRegistry,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val instanceId = "${InetAddress.getLocalHost().hostName}-${ProcessHandle.current().pid()}"
    private val activeJobs = AtomicInteger(0)
    private val executor = Executors.newVirtualThreadPerTaskExecutor()

    // Track job start times for duration calculation
    private val jobStartTimes = ConcurrentHashMap<GenerationRequestId, Long>()

    // Micrometer counters for observability
    private val jobsClaimedCounter = meterRegistry.counter("epistola.jobs.claimed.total")
    private val jobsCompletedCounter = meterRegistry.counter("epistola.jobs.completed.total")
    private val jobsFailedCounter = meterRegistry.counter("epistola.jobs.failed.total")

    /**
     * Poll for pending jobs and claim a batch if capacity allows.
     * Runs on a fixed delay to ensure continuous polling without overlapping.
     *
     * Tracks job processing time and reports to AdaptiveBatchSizer for batch size adjustment.
     * Claims multiple jobs per poll cycle based on adaptive batch size and available capacity.
     */
    @Scheduled(fixedDelayString = "\${epistola.generation.polling.interval-ms:5000}")
    fun poll() {
        // Respect maxConcurrentJobs limit
        val currentActive = activeJobs.get()
        if (currentActive >= properties.maxConcurrentJobs) {
            logger.debug("Max concurrent jobs reached ({}), skipping poll", properties.maxConcurrentJobs)
            return
        }

        // Calculate how many jobs we can claim
        val availableSlots = properties.maxConcurrentJobs - currentActive
        val requestedBatchSize = batchSizer.getCurrentBatchSize()
        val actualBatchSize = minOf(requestedBatchSize, availableSlots)

        if (actualBatchSize <= 0) {
            return
        }

        // Claim batch of pending requests
        val requests = claimPendingRequests(actualBatchSize)

        if (requests.isEmpty()) {
            return
        }

        logger.debug(
            "Claimed {} jobs (requested batch: {}, available slots: {})",
            requests.size,
            requestedBatchSize,
            availableSlots,
        )

        // Increment counter for all claimed jobs
        jobsClaimedCounter.increment(requests.size.toDouble())

        // Submit each job to executor
        requests.forEach { request ->
            activeJobs.incrementAndGet()

            // Track start time for duration calculation
            jobStartTimes[request.id] = System.currentTimeMillis()

            logger.info("Processing request {} (active jobs: {})", request.id.value, activeJobs.get())

            // Execute on virtual thread, don't block the scheduler
            executor.submit {
                try {
                    jobExecutor.execute(request)
                    jobsCompletedCounter.increment()
                } catch (e: Exception) {
                    logger.error("Job execution failed for request {}: {}", request.id.value, e.message, e)
                    jobsFailedCounter.increment()
                    markRequestFailed(request.id, e.message)
                } finally {
                    // Calculate duration and report to adaptive batch sizer
                    val startTime = jobStartTimes.remove(request.id)
                    if (startTime != null) {
                        val duration = System.currentTimeMillis() - startTime
                        batchSizer.recordJobCompletion(duration)
                    }
                    activeJobs.decrementAndGet()
                }
            }
        }
    }

    /**
     * Claim up to N pending requests atomically using SELECT FOR UPDATE SKIP LOCKED.
     * This ensures only one instance can claim each request even under concurrent polling.
     *
     * @param batchSize Maximum number of requests to claim
     * @return List of claimed requests (may be empty if no pending requests available)
     */
    private fun claimPendingRequests(batchSize: Int): List<DocumentGenerationRequest> = jdbi.inTransaction<List<DocumentGenerationRequest>, Exception> { handle ->
        // PostgreSQL: Use CTE to select and update atomically
        handle.createQuery(
            """
                WITH claimed AS (
                    SELECT id FROM document_generation_requests
                    WHERE status = 'PENDING'
                    ORDER BY created_at
                    LIMIT :batchSize
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
            .bind("batchSize", batchSize)
            .bind("instanceId", instanceId)
            .mapTo<DocumentGenerationRequest>()
            .list()
    }

    /**
     * Mark a request as failed when job execution throws an exception.
     */
    private fun markRequestFailed(requestId: GenerationRequestId, errorMessage: String?) {
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

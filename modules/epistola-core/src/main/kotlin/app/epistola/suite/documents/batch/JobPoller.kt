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
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Scheduled poller that claims and executes pending document generation jobs.
 *
 * Uses polling with `SELECT FOR UPDATE SKIP LOCKED` for safe multi-instance distribution.
 * Jobs are executed on virtual threads to avoid blocking the scheduler.
 *
 * Supports adaptive batch sizing: claims multiple jobs per poll cycle based on system
 * performance, measured via job processing time.
 *
 * Uses a drain loop pattern for efficient queue processing:
 * - When a job completes, it signals for more work immediately
 * - The drain loop continuously claims jobs until the queue is empty or at capacity
 * - Scheduled polling serves as a fallback safety net
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

    // Dedicated executor for job processing (virtual threads)
    private val jobThreadExecutor = Executors.newVirtualThreadPerTaskExecutor()

    // Dedicated single-thread executor for drain loop (serializes claiming)
    private val drainExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "job-poller-drain").apply { isDaemon = true }
    }

    // Flag to coalesce multiple drain requests into one
    private val drainRequested = AtomicBoolean(false)

    // Track job start times for duration calculation
    private val jobStartTimes = ConcurrentHashMap<GenerationRequestId, Long>()

    // Micrometer counters for observability
    private val jobsClaimedCounter = meterRegistry.counter("epistola.jobs.claimed.total")
    private val jobsCompletedCounter = meterRegistry.counter("epistola.jobs.completed.total")
    private val jobsFailedCounter = meterRegistry.counter("epistola.jobs.failed.total")

    init {
        logger.info(
            "Job poller started: instanceId={}, maxConcurrentJobs={}, pollInterval={}ms",
            instanceId,
            properties.maxConcurrentJobs,
            properties.intervalMs,
        )
    }

    /**
     * Wait until all active jobs have completed, up to the given timeout.
     * Useful for test cleanup to avoid deadlocks when deleting test data.
     */
    fun awaitIdle(timeout: Duration = Duration.ofSeconds(10)) {
        val deadline = System.currentTimeMillis() + timeout.toMillis()
        while (activeJobs.get() > 0) {
            if (System.currentTimeMillis() > deadline) {
                logger.warn("Timed out waiting for {} active jobs to complete", activeJobs.get())
                break
            }
            Thread.sleep(50)
        }
    }

    /**
     * Scheduled poll that triggers a drain. Serves as fallback safety net.
     * The primary driver is on-completion re-polling via [requestDrain].
     */
    @Scheduled(fixedDelayString = "\${epistola.generation.polling.interval-ms:5000}")
    fun scheduledPoll() {
        requestDrain()
    }

    /**
     * Signal that draining should happen. Safe to call from any thread.
     * Multiple calls are coalesced - only one drain runs at a time.
     */
    fun requestDrain() {
        if (drainRequested.compareAndSet(false, true)) {
            drainExecutor.submit { drain() }
        }
    }

    /**
     * Continuously claim and process jobs until queue empty or at capacity.
     * Runs on dedicated single thread - no concurrency issues with claiming.
     */
    private fun drain() {
        while (true) {
            drainRequested.set(false) // Reset flag, will be set again if more work needed

            // Keep claiming until at capacity or no more work
            while (activeJobs.get() < properties.maxConcurrentJobs) {
                val availableSlots = properties.maxConcurrentJobs - activeJobs.get()
                val requestedBatchSize = batchSizer.getCurrentBatchSize()
                val actualBatchSize = minOf(requestedBatchSize, availableSlots)

                if (actualBatchSize <= 0) {
                    break
                }

                val requests = claimPendingRequests(actualBatchSize)
                if (requests.isEmpty()) {
                    logger.debug(
                        "No pending jobs available | Batch size: {}, Active: {}/{}",
                        requestedBatchSize,
                        activeJobs.get(),
                        properties.maxConcurrentJobs,
                    )
                    break // Queue empty
                }

                logger.info(
                    "Claimed {} job(s) | Requested batch: {}, Available slots: {}, Active: {}/{}",
                    requests.size,
                    requestedBatchSize,
                    availableSlots,
                    activeJobs.get(),
                    properties.maxConcurrentJobs,
                )

                // Increment counter for all claimed jobs
                jobsClaimedCounter.increment(requests.size.toDouble())

                // Submit each job to executor
                requests.forEach { request ->
                    activeJobs.incrementAndGet()

                    // Track start time for duration calculation
                    jobStartTimes[request.id] = System.currentTimeMillis()

                    logger.debug("Processing request {} (active jobs: {})", request.id.value, activeJobs.get())

                    // Execute on virtual thread, don't block the drain thread
                    jobThreadExecutor.submit {
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
                            val newActiveCount = activeJobs.decrementAndGet()
                            if (startTime != null) {
                                val duration = System.currentTimeMillis() - startTime
                                batchSizer.recordJobCompletion(duration)
                                logger.info(
                                    "Job completed: {} in {}ms | Active: {}/{}",
                                    request.id.value,
                                    duration,
                                    newActiveCount,
                                    properties.maxConcurrentJobs,
                                )
                            }
                            // Signal: slot freed, check for more work immediately
                            requestDrain()
                        }
                    }
                }
            }

            // Check if another drain was requested while we were working
            if (!drainRequested.get()) {
                break // No more requests, exit drain loop
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
                          document_generation_requests.batch_id,
                          document_generation_requests.tenant_id,
                          document_generation_requests.template_id,
                          document_generation_requests.variant_id,
                          document_generation_requests.version_id,
                          document_generation_requests.environment_id,
                          document_generation_requests.data,
                          document_generation_requests.filename,
                          document_generation_requests.correlation_id,
                          document_generation_requests.document_id,
                          document_generation_requests.status,
                          document_generation_requests.claimed_by,
                          document_generation_requests.claimed_at,
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

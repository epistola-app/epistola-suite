// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents.batch

import app.epistola.suite.cluster.ClusterProperties
import app.epistola.suite.cluster.schedules.ClusterScheduledTask
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskDefinition
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskExecutionScope
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskHandler
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskSchedule
import app.epistola.suite.common.ids.GenerationRequestKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.documents.JobPollingProperties
import app.epistola.suite.documents.model.DocumentGenerationRequest
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.MediatorContext
import app.epistola.suite.security.SystemUser
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import jakarta.annotation.PreDestroy
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.stereotype.Component
import java.net.InetAddress
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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
    private val mediator: Mediator,
) : ClusterScheduledTaskHandler {
    private val logger = LoggerFactory.getLogger(javaClass)
    override val taskType: String = TASK_TYPE
    private val instanceId = "${InetAddress.getLocalHost().hostName}-${ProcessHandle.current().pid()}"
    private val activeJobs = AtomicInteger(0)
    private val shuttingDown = AtomicBoolean(false)

    // Latch that signals when all active jobs have completed (activeJobs reaches 0)
    @Volatile
    private var idleLatch = CountDownLatch(0)
    private val idleLatchLock = Any()

    // Dedicated executor for job processing on PLATFORM threads — deliberately NOT
    // virtual threads. PDF rendering is CPU-bound, so virtual threads add nothing, and
    // worse: a virtual thread can unmount while holding the Spring Boot nested-jar loader
    // monitor (JEP 491), which under a concurrent first-load burst deadlocks the fat-jar
    // class loader against the JVM per-class-name load lock — an invisible-holder wedge
    // that no amount of warmup fully prevents (#724, reproduced via thread dump). Platform
    // threads stay mounted, so the monitor holder always makes progress: contention, never
    // deadlock. Bounded to maxConcurrentJobs since the claim gate already caps in-flight
    // work at that; threads are created on demand and are daemons.
    private val jobThreadExecutor: ExecutorService = Executors.newFixedThreadPool(
        properties.maxConcurrentJobs.coerceAtLeast(1),
        Thread.ofPlatform().name("job-render-", 0).daemon(true).factory(),
    )

    // Dedicated single-thread executor for drain loop (serializes claiming)
    private val drainExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "job-poller-drain").apply { isDaemon = true }
    }

    // Flag to coalesce multiple drain requests into one
    private val drainRequested = AtomicBoolean(false)

    // Cached pending count, updated each drain cycle
    private val pendingCount = AtomicInteger(0)

    // Wall-clock of the last drain cycle, for the liveness heartbeat exposed to
    // the health indicator. The scheduled poll fires every interval-ms even when
    // idle, so a stale value means the poller thread is wedged.
    private val lastPollAtMs = AtomicLong(System.currentTimeMillis())

    // Micrometer counters for observability
    private val jobsClaimedCounter = meterRegistry.counter("epistola.jobs.claimed.total")
    private val jobsCompletedCounter = meterRegistry.counter("epistola.jobs.completed.total")
    private val jobsFailedCounter = meterRegistry.counter("epistola.jobs.failed.total")

    // Drain outcome per claim attempt: how often the poller finds work vs wakes to an
    // empty queue — the "busy vs no-jobs" signal, without querying the DB. The rate of
    // outcome=empty relative to outcome=found approximates idle time.
    private val drainFoundCounter = meterRegistry.counter("epistola.jobs.drain.batches", "outcome", "found")
    private val drainEmptyCounter = meterRegistry.counter("epistola.jobs.drain.batches", "outcome", "empty")

    // Time a request sat PENDING before this node claimed it (created_at → claimed_at),
    // computed from the in-memory request row — the "waiting" bucket, no DB query. Pairs
    // with epistola.jobs.duration (the "generation" bucket) to account for end-to-end time.
    private val queueWaitTimer = Timer.builder("epistola.generation.queue.wait")
        .description("Time a generation request waited in PENDING before being claimed")
        .register(meterRegistry)

    init {
        // Register gauges for active jobs, max concurrent limit, and queue depth
        meterRegistry.gauge("epistola.jobs.active", activeJobs) { it.get().toDouble() }
        meterRegistry.gauge("epistola.jobs.max_concurrent", properties) { it.maxConcurrentJobs.toDouble() }
        meterRegistry.gauge("epistola.generation.queue.depth", pendingCount) { it.get().toDouble() }

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
        val latch = idleLatch
        if (activeJobs.get() == 0) return
        if (!latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            logger.warn("Timed out waiting for {} active jobs to complete", activeJobs.get())
        }
    }

    @PreDestroy
    fun shutdown() {
        logger.info("Job poller shutting down... (active jobs: {})", activeJobs.get())
        shuttingDown.set(true)

        // Wait briefly for in-flight jobs to finish
        awaitIdle(Duration.ofSeconds(5))

        drainExecutor.shutdownNow()
        jobThreadExecutor.shutdownNow()

        try {
            val released = releaseOwnInProgressClaims()
            if (released > 0) {
                logger.info("Released {} in-progress claims back to PENDING on shutdown", released)
            }
        } catch (e: Exception) {
            // Datasource may already be closing during shutdown. Don't fail the shutdown
            // path on this — StaleJobRecovery is the safety net.
            logger.warn("Failed to release in-progress claims on shutdown: {}", e.message)
        }

        logger.info("Job poller shut down (remaining active jobs: {})", activeJobs.get())
    }

    /**
     * Release any IN_PROGRESS rows still claimed by THIS instance back to PENDING.
     * Called on graceful shutdown so dev restarts and rolling pod deploys don't
     * leave orphaned claims that [StaleJobRecovery] only frees after
     * `epistola.generation.polling.stale-timeout-minutes` (default 10 min).
     * Crashes still rely on that fallback; this only handles the orderly-exit path.
     */
    private fun releaseOwnInProgressClaims(): Int = jdbi.withHandle<Int, Exception> { handle ->
        handle.createUpdate(
            """
            UPDATE document_generation_requests
            SET status = 'PENDING',
                claimed_by = NULL,
                claimed_at = NULL,
                started_at = NULL
            WHERE status = 'IN_PROGRESS' AND claimed_by = :instanceId
            """,
        )
            .bind("instanceId", instanceId)
            .execute()
    }

    @Bean
    fun jobPollerScheduledTaskDefinition(): ClusterScheduledTaskDefinition = ClusterScheduledTaskDefinition(
        taskKey = TASK_KEY,
        routingKey = ROUTING_KEY,
        taskType = TASK_TYPE,
        schedule = ClusterScheduledTaskSchedule.FixedDelay(properties.intervalMs),
        executionScope = ClusterScheduledTaskExecutionScope.EACH_CAPABLE_NODE,
        // Render pipeline runs only on nodes advertising the pdf-render capability (the suite by
        // default, plus dedicated apps/pdfrender workers), never on suite-only control nodes.
        requiredCapability = ClusterProperties.PDF_RENDER_CAPABILITY,
    )

    override fun handle(task: ClusterScheduledTask) {
        requestDrain()
    }

    /**
     * Signal that draining should happen. Safe to call from any thread.
     * Multiple calls are coalesced - only one drain runs at a time.
     */
    fun requestDrain() {
        if (shuttingDown.get()) return
        if (drainRequested.compareAndSet(false, true)) {
            drainExecutor.submit(MediatorContext.runnable(mediator) { drain() })
        }
    }

    /**
     * Milliseconds since the last drain cycle. Consumed by the job-poller health
     * indicator: the scheduled poll fires every `interval-ms` even when idle, so
     * a large value means the drain thread is wedged.
     */
    fun lastPollAgeMillis(): Long = System.currentTimeMillis() - lastPollAtMs.get()

    /**
     * Continuously claim and process jobs until queue empty or at capacity.
     * Runs on dedicated single thread - no concurrency issues with claiming.
     */
    private fun drain() {
        while (true) {
            drainRequested.set(false) // Reset flag, will be set again if more work needed
            lastPollAtMs.set(System.currentTimeMillis()) // heartbeat for the health indicator

            // Update pending count for metrics (lightweight query, piggybacks on poll cycle)
            updatePendingCount()

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
                    drainEmptyCounter.increment()
                    pendingCount.set(0)
                    logger.debug(
                        "No pending jobs available | Batch size: {}, Active: {}/{}",
                        requestedBatchSize,
                        activeJobs.get(),
                        properties.maxConcurrentJobs,
                    )
                    break // Queue empty
                }
                drainFoundCounter.increment()

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
                    synchronized(idleLatchLock) {
                        if (activeJobs.getAndIncrement() == 0) {
                            idleLatch = CountDownLatch(1)
                        }
                    }

                    logger.debug("Processing request {} (active jobs: {})", request.id.value, activeJobs.get())

                    // Execute on virtual thread, don't block the drain thread
                    jobThreadExecutor.submit(
                        MediatorContext.runnable(mediator, SystemUser.principalForTenant(request.tenantKey)) {
                            try {
                                executeClaimed(request)
                            } finally {
                                val newActiveCount = synchronized(idleLatchLock) {
                                    val count = activeJobs.decrementAndGet()
                                    if (count == 0) {
                                        idleLatch.countDown()
                                    }
                                    count
                                }
                                logger.debug("Slot freed after {} (active jobs: {})", request.id.value, newActiveCount)
                                // Signal: slot freed, check for more work immediately
                                requestDrain()
                            }
                        },
                    )
                }
            }

            // Check if another drain was requested while we were working
            if (!drainRequested.get()) {
                break // No more requests, exit drain loop
            }
        }
    }

    /**
     * Synchronously claims and executes every PENDING request for [tenantKey] on the
     * **calling thread**, returning the number processed. This is the explicit,
     * tenant-scoped counterpart to the autonomous [drain]: deterministic (no virtual
     * threads, no `awaitIdle`) and isolated (claims only this tenant's rows), which is
     * what tests want when they actually need a document generated, and what an
     * operator "process now for tenant X" action would use.
     *
     * Each job runs under the tenant's system principal via [MediatorContext.runnable],
     * which also captures the caller's clock — so under the deterministic test substrate
     * generation executes in the bound test clock.
     */
    fun drainTenant(tenantKey: TenantKey): Int {
        var processed = 0
        while (true) {
            val requests = claimPendingRequests(properties.maxConcurrentJobs, tenantKey)
            if (requests.isEmpty()) break
            jobsClaimedCounter.increment(requests.size.toDouble())
            requests.forEach { request ->
                MediatorContext.runnable(mediator, SystemUser.principalForTenant(request.tenantKey)) {
                    executeClaimed(request)
                }.run()
                processed++
            }
        }
        return processed
    }

    /**
     * Executes one claimed request and records its outcome: runs the executor, bumps the
     * completed/failed counters and the duration timer, and marks the row FAILED on
     * exception (so a partial batch failure does not abort the rest). Shared by the
     * autonomous drain and [drainTenant] so both have identical metrics and failure
     * semantics. Must run within a [MediatorContext] bound to the request's tenant principal.
     */
    private fun executeClaimed(request: DocumentGenerationRequest) {
        recordQueueWait(request)
        val sample = Timer.start(meterRegistry)
        var outcome = "success"
        try {
            jobExecutor.execute(request)
            jobsCompletedCounter.increment()
        } catch (e: Exception) {
            outcome = "failure"
            logger.error("Job execution failed for request {}: {}", request.id.value, e.message, e)
            jobsFailedCounter.increment()
            markRequestFailed(request.id, e.message)
        }
        val durationMs = sample.stop(
            Timer.builder("epistola.jobs.duration").tag("outcome", outcome).register(meterRegistry),
        ) / 1_000_000
        batchSizer.recordJobCompletion(durationMs)
        logger.info("Job completed: {} in {}ms (outcome={})", request.id.value, durationMs, outcome)
    }

    /**
     * Records how long [request] waited in PENDING before this node claimed it
     * (created_at → claimed_at), from the in-memory row — no DB query. Together with
     * `epistola.jobs.duration` this attributes end-to-end time to waiting vs generating.
     */
    private fun recordQueueWait(request: DocumentGenerationRequest) {
        val claimedAt = request.claimedAt ?: return
        val wait = Duration.between(request.createdAt, claimedAt)
        if (!wait.isNegative) queueWaitTimer.record(wait)
    }

    /**
     * Update the cached pending request count for metrics.
     */
    private fun updatePendingCount() {
        try {
            val count = jdbi.withHandle<Int, Exception> { handle ->
                handle.createQuery("SELECT COUNT(*) FROM document_generation_requests WHERE status = 'PENDING'")
                    .mapTo(Int::class.java)
                    .one()
            }
            pendingCount.set(count)
        } catch (e: Exception) {
            logger.debug("Failed to update pending count: {}", e.message)
        }
    }

    /**
     * Claim up to N pending requests atomically using SELECT FOR UPDATE SKIP LOCKED.
     * This ensures only one instance can claim each request even under concurrent polling.
     *
     * @param batchSize Maximum number of requests to claim
     * @return List of claimed requests (may be empty if no pending requests available)
     */
    private fun claimPendingRequests(batchSize: Int, tenantKey: TenantKey? = null): List<DocumentGenerationRequest> = jdbi.inTransaction<List<DocumentGenerationRequest>, Exception> { handle ->
        // PostgreSQL: Use CTE to select and update atomically. The optional tenant filter
        // backs the explicit, tenant-scoped drain (drainTenant) so processing one tenant's
        // queue cannot claim another tenant's pending work.
        val tenantFilter = if (tenantKey != null) "AND tenant_key = :tenant" else ""
        handle.createQuery(
            """
                WITH claimed AS (
                    SELECT id FROM document_generation_requests
                    WHERE status = 'PENDING'
                    $tenantFilter
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
                          document_generation_requests.tenant_key,
                          document_generation_requests.catalog_key,
                          document_generation_requests.template_key,
                          document_generation_requests.variant_key,
                          document_generation_requests.version_key,
                          document_generation_requests.environment_key,
                          document_generation_requests.data,
                          document_generation_requests.filename,
                          document_generation_requests.correlation_id,
                          document_generation_requests.routing_key,
                          document_generation_requests.document_key,
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
            .apply { if (tenantKey != null) bind("tenant", tenantKey) }
            .mapTo<DocumentGenerationRequest>()
            .list()
    }

    /**
     * Mark a request as failed when job execution throws an exception.
     */
    private fun markRequestFailed(requestId: GenerationRequestKey, errorMessage: String?) {
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

    companion object {
        const val TASK_KEY = "core.document-job-poller"
        const val ROUTING_KEY = "system:core.document-job-poller"
        const val TASK_TYPE = "core.document-job-poller"
    }
}

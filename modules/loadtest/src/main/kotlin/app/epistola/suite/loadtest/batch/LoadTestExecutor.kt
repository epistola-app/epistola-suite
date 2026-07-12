package app.epistola.suite.loadtest.batch

import app.epistola.suite.common.ids.BatchKey
import app.epistola.suite.common.ids.DocumentKey
import app.epistola.suite.common.ids.GenerationRequestKey
import app.epistola.suite.documents.commands.BatchGenerationItem
import app.epistola.suite.documents.commands.GenerateDocumentBatch
import app.epistola.suite.loadtest.model.LoadTestMetrics
import app.epistola.suite.loadtest.model.LoadTestRequestKey
import app.epistola.suite.loadtest.model.LoadTestRun
import app.epistola.suite.loadtest.model.LoadTestRunKey
import app.epistola.suite.loadtest.model.LoadTestStatus
import app.epistola.suite.mediator.Mediator
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.OffsetDateTime

/**
 * Executes load test runs by generating documents using batch submission.
 *
 * Uses GenerateDocumentBatch to submit all requests in a single database operation,
 * then polls for completion. Much faster than individual submissions for large batches.
 */
@Component
class LoadTestExecutor(
    private val jdbi: Jdbi,
    private val mediator: Mediator,
    private val objectMapper: ObjectMapper,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Track job submissions.
     */
    private data class SubmittedJob(
        val sequenceNumber: Int,
        val generationRequestId: GenerationRequestKey,
        val correlationId: String,
    )

    /**
     * Execute a load test run.
     *
     * Generates [targetCount] documents using batch submission for efficiency.
     * Tracks timing for each request and calculates aggregated metrics.
     */
    fun execute(run: LoadTestRun) {
        val startTime = System.currentTimeMillis()

        // Check if run was cancelled before starting
        if (isRunCancelled(run.id)) {
            logger.info("Load test run {} was cancelled, skipping execution", run.id)
            return
        }

        logger.info(
            "Executing load test run {} - {} documents",
            run.id,
            run.targetCount,
        )

        logger.info("Submitting {} generation jobs in a single batch...", run.targetCount)

        // Build batch items
        val batchItems = (1..run.targetCount).map { sequenceNumber ->
            BatchGenerationItem(
                catalogKey = run.catalogKey,
                templateId = run.templateKey,
                variantId = run.variantKey,
                versionId = run.versionKey,
                environmentId = run.environmentKey,
                data = run.testData,
                filename = "loadtest-${run.id}-$sequenceNumber.pdf",
                correlationId = "loadtest-${run.id}-$sequenceNumber",
            )
        }

        // Submit entire batch at once
        val batchId = try {
            mediator.send(
                GenerateDocumentBatch(
                    tenantId = run.tenantKey,
                    items = batchItems,
                ),
            )
        } catch (e: Exception) {
            logger.error("Failed to submit batch for load test run {}: {}", run.id, e.message)
            finalizeRun(run.id, null, createEmptyMetrics(), 0, run.targetCount, wasCancelled = true)
            return
        }

        logger.info("Submitted batch {} with {} requests for load test run {}", batchId.value, run.targetCount, run.id)

        // Store batch_id in load test run for linking to document_generation_requests
        storeBatchId(run.id, batchId)

        // Query back the created request IDs
        val submittedJobs = queryBatchRequests(batchId, run.id)

        logger.info("Retrieved {} job IDs, waiting for async processing to complete...", submittedJobs.size)

        // Poll database to wait for all jobs to reach terminal state
        val (jobResults, wasCancelled) = pollForJobCompletion(run.id, batchId, submittedJobs)

        val endTime = System.currentTimeMillis()
        val totalDurationMs = endTime - startTime

        // Convert job results to RequestResult format for metrics calculation
        val requestResults = jobResults.map { job ->
            RequestResult(
                id = LoadTestRequestKey.generate(),
                sequenceNumber = job.sequenceNumber,
                startedAt = job.createdAt,
                durationMs = job.durationMs,
                success = job.success,
                errorMessage = job.errorMessage,
                errorType = job.errorType,
                documentId = job.documentId,
            )
        }

        val completedCount = requestResults.count { it.success }
        val failedCount = requestResults.count { !it.success }

        // Queue wait (created → started): time a request sat PENDING before a node picked
        // it up, separate from render time. Computed from the polled timestamps, no extra
        // query — surfaces the "waiting" bucket in the run's own metrics.
        val queueWaits = jobResults.filter { it.startedAt != null }.map { it.queueWaitMs }.sorted()
        val avgQueueWaitMs = if (queueWaits.isEmpty()) 0.0 else queueWaits.average()
        val p95QueueWaitMs = percentile(queueWaits, 0.95)

        logger.info(
            "Load test run {} completed: {}/{} succeeded, {} failed in {}ms (avg queue wait {}ms, p95 {}ms)",
            run.id,
            completedCount,
            run.targetCount,
            failedCount,
            totalDurationMs,
            avgQueueWaitMs.toLong(),
            p95QueueWaitMs,
        )

        // Calculate and save metrics (no longer save to load_test_requests - query from source instead)
        val metrics = calculateMetrics(requestResults, totalDurationMs)
        finalizeRun(run.id, batchId, metrics, completedCount, failedCount, wasCancelled, avgQueueWaitMs, p95QueueWaitMs)
    }

    /**
     * Result of an async job (from database).
     */
    private data class JobResult(
        val sequenceNumber: Int,
        val generationRequestId: GenerationRequestKey,
        val createdAt: OffsetDateTime,
        val startedAt: OffsetDateTime?,
        val completedAt: OffsetDateTime?,
        val status: String,
        val errorMessage: String?,
        val documentId: DocumentKey?,
    ) {
        val success: Boolean get() = status == "COMPLETED"
        val durationMs: Long get() {
            return if (startedAt != null && completedAt != null) {
                Duration.between(startedAt, completedAt).toMillis()
            } else {
                0
            }
        }

        /** Time the request waited in PENDING before a node picked it up (created → started). */
        val queueWaitMs: Long get() = if (startedAt != null) Duration.between(createdAt, startedAt).toMillis() else 0
        val errorType: String? get() = if (!success && errorMessage != null) {
            when {
                errorMessage.contains("validation", ignoreCase = true) -> "VALIDATION"
                errorMessage.contains("timeout", ignoreCase = true) -> "TIMEOUT"
                errorMessage.contains("not found", ignoreCase = true) -> "CONFIGURATION"
                else -> "GENERATION"
            }
        } else {
            null
        }
    }

    /**
     * Poll the database until all of the run's generation requests reach a terminal
     * state, then return their per-row results (for metric calculation) plus a
     * cancellation flag.
     *
     * Each poll asks the DB only for **status counts** — one row, resolved by the
     * `(batch_id, status)` index, with no per-request rows shipped or mapped. The full
     * per-row result set is fetched exactly **once**, at the end, so the load test's own
     * DB footprint stays a rounding error in the load it is measuring (previously it
     * re-fetched and re-mapped every request on every 500ms poll).
     */
    private fun pollForJobCompletion(
        runId: LoadTestRunKey,
        batchId: BatchKey,
        jobs: List<SubmittedJob>,
    ): Pair<List<JobResult>, Boolean> {
        val jobsById = jobs.associateBy { it.generationRequestId }
        val pollIntervalMs = POLL_INTERVAL_MS
        val maxWaitTimeMs = MAX_WAIT_TIME_MS
        val startTime = System.currentTimeMillis()
        var wasCancelled = false

        // Cheap per-poll progress: completed / failed / still-running counts in one row.
        fun counts(): Triple<Int, Int, Int> = jdbi.withHandle<Triple<Int, Int, Int>, Exception> { handle ->
            handle.createQuery(
                """
                SELECT count(*) FILTER (WHERE status = 'COMPLETED')                 AS completed,
                       count(*) FILTER (WHERE status = 'FAILED')                    AS failed,
                       count(*) FILTER (WHERE status IN ('PENDING', 'IN_PROGRESS')) AS in_progress
                FROM document_generation_requests
                WHERE batch_id = :batchId
                """,
            )
                .bind("batchId", batchId)
                .map { rs, _ -> Triple(rs.getInt("completed"), rs.getInt("failed"), rs.getInt("in_progress")) }
                .one()
        }

        // Full per-row results — fetched once, at the end, for metric calculation.
        fun fetchResults(): List<JobResult> = jdbi.withHandle<List<JobResult>, Exception> { handle ->
            handle.createQuery(
                """
                SELECT id, created_at, started_at, completed_at, status, error_message, document_key
                FROM document_generation_requests
                WHERE batch_id = :batchId
                """,
            )
                .bind("batchId", batchId)
                .map { rs, _ ->
                    val requestId = GenerationRequestKey.of(rs.getObject("id") as java.util.UUID)
                    val job = jobsById.getValue(requestId)
                    JobResult(
                        sequenceNumber = job.sequenceNumber,
                        generationRequestId = requestId,
                        createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
                        startedAt = rs.getObject("started_at", OffsetDateTime::class.java),
                        completedAt = rs.getObject("completed_at", OffsetDateTime::class.java),
                        status = rs.getString("status"),
                        errorMessage = rs.getString("error_message"),
                        documentId = rs.getObject("document_key", java.util.UUID::class.java)?.let { DocumentKey.of(it) },
                    )
                }
                .list()
        }

        while (true) {
            if (isRunCancelled(runId)) {
                logger.warn("Load test run {} cancelled while waiting for jobs", runId)
                wasCancelled = true
                break
            }
            if (System.currentTimeMillis() - startTime > maxWaitTimeMs) {
                logger.error("Load test run {} timed out waiting for jobs after {}ms", runId, maxWaitTimeMs)
                break
            }

            val (completed, failed, inProgress) = counts()
            logger.debug("Load test progress: {} completed, {} failed, {} in progress", completed, failed, inProgress)
            updateProgress(runId, completed, failed)

            // in_progress == 0 means every request is terminal (COMPLETED/FAILED/CANCELLED).
            if (inProgress == 0) {
                logger.info("All jobs terminal for load test run {} ({} completed, {} failed)", runId, completed, failed)
                return Pair(fetchResults(), wasCancelled)
            }

            Thread.sleep(pollIntervalMs)
        }

        // Cancelled or timed out: fetch the rows in whatever state they are now.
        return Pair(fetchResults(), wasCancelled)
    }

    /**
     * Store batch_id in load test run for linking to document_generation_requests.
     * Also stamps the progress heartbeat so the early phase — after claim but before
     * the first poll cycle — is not falsely seen as stale (#725).
     */
    private fun storeBatchId(runId: LoadTestRunKey, batchId: BatchKey) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                UPDATE load_test_runs
                SET batch_id = :batchId,
                    last_progress_at = NOW()
                WHERE id = :runId
                """,
            )
                .bind("runId", runId)
                .bind("batchId", batchId)
                .execute()
        }
    }

    /**
     * Update progress counts in the database and stamp the progress heartbeat.
     *
     * `last_progress_at` advances on every poll cycle (every ~500ms) for as long as
     * this executor is alive and polling, so stale-run recovery can distinguish a
     * healthy long run from a genuinely abandoned one (#725).
     */
    private fun updateProgress(runId: LoadTestRunKey, completedCount: Int, failedCount: Int) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                UPDATE load_test_runs
                SET completed_count = :completedCount,
                    failed_count = :failedCount,
                    last_progress_at = NOW()
                WHERE id = :runId
                """,
            )
                .bind("runId", runId)
                .bind("completedCount", completedCount)
                .bind("failedCount", failedCount)
                .execute()
        }
    }

    /**
     * Check if a run has been cancelled.
     */
    private fun isRunCancelled(runId: LoadTestRunKey): Boolean = jdbi.withHandle<Boolean, Exception> { handle ->
        handle.createQuery(
            """
            SELECT status = 'CANCELLED' FROM load_test_runs WHERE id = :runId
            """,
        )
            .bind("runId", runId)
            .mapTo<Boolean>()
            .one()
    }

    /**
     * Query all requests created for a batch, ordered by correlation_id sequence.
     */
    private fun queryBatchRequests(batchId: BatchKey, runId: LoadTestRunKey): List<SubmittedJob> = jdbi.withHandle<List<SubmittedJob>, Exception> { handle ->
        handle.createQuery(
            """
                SELECT id, correlation_id
                FROM document_generation_requests
                WHERE batch_id = :batchId
                ORDER BY correlation_id
                """,
        )
            .bind("batchId", batchId)
            .map { rs, _ ->
                val correlationId = rs.getString("correlation_id")
                val sequenceNumber = correlationId.substringAfterLast("-").toInt()
                SubmittedJob(
                    sequenceNumber = sequenceNumber,
                    generationRequestId = GenerationRequestKey(rs.getObject("id", java.util.UUID::class.java)),
                    correlationId = correlationId,
                )
            }
            .list()
    }

    /**
     * Create empty metrics for failed batch submission.
     */
    private fun createEmptyMetrics(): LoadTestMetrics = LoadTestMetrics(
        totalDurationMs = 0,
        avgResponseTimeMs = 0.0,
        minResponseTimeMs = 0,
        maxResponseTimeMs = 0,
        p50ResponseTimeMs = 0,
        p95ResponseTimeMs = 0,
        p99ResponseTimeMs = 0,
        requestsPerSecond = 0.0,
        successRatePercent = 0.0,
        errorSummary = emptyMap(),
    )

    /**
     * Calculate aggregated metrics from request results.
     */
    private fun calculateMetrics(results: List<RequestResult>, totalDurationMs: Long): LoadTestMetrics {
        val successfulResults = results.filter { it.success }
        val durations = successfulResults.map { it.durationMs }.sorted()

        if (durations.isEmpty()) {
            // All requests failed - return zero metrics
            return LoadTestMetrics(
                totalDurationMs = totalDurationMs,
                avgResponseTimeMs = 0.0,
                minResponseTimeMs = 0,
                maxResponseTimeMs = 0,
                p50ResponseTimeMs = 0,
                p95ResponseTimeMs = 0,
                p99ResponseTimeMs = 0,
                requestsPerSecond = 0.0,
                successRatePercent = 0.0,
                errorSummary = results
                    .filter { !it.success }
                    .groupingBy { it.errorType ?: "UNKNOWN" }
                    .eachCount(),
            )
        }

        val avgResponseTime = durations.average()
        val minResponseTime = durations.first()
        val maxResponseTime = durations.last()
        val p50 = percentile(durations, 0.50)
        val p95 = percentile(durations, 0.95)
        val p99 = percentile(durations, 0.99)

        val requestsPerSecond = if (totalDurationMs > 0) {
            results.size / (totalDurationMs / 1000.0)
        } else {
            0.0
        }

        val successRate = (successfulResults.size.toDouble() / results.size.toDouble()) * 100.0

        val errorSummary = results
            .filter { !it.success }
            .groupingBy { it.errorType ?: "UNKNOWN" }
            .eachCount()

        return LoadTestMetrics(
            totalDurationMs = totalDurationMs,
            avgResponseTimeMs = avgResponseTime,
            minResponseTimeMs = minResponseTime,
            maxResponseTimeMs = maxResponseTime,
            p50ResponseTimeMs = p50,
            p95ResponseTimeMs = p95,
            p99ResponseTimeMs = p99,
            requestsPerSecond = requestsPerSecond,
            successRatePercent = successRate,
            errorSummary = errorSummary,
        )
    }

    /**
     * Calculate percentile value from sorted list.
     */
    private fun percentile(sorted: List<Long>, p: Double): Long {
        if (sorted.isEmpty()) return 0
        val index = ((sorted.size - 1) * p).toInt()
        return sorted[index]
    }

    /**
     * Finalize load test run with metrics.
     */
    private fun finalizeRun(
        runId: LoadTestRunKey,
        batchId: BatchKey?,
        metrics: LoadTestMetrics,
        completedCount: Int,
        failedCount: Int,
        wasCancelled: Boolean,
        avgQueueWaitMs: Double = 0.0,
        p95QueueWaitMs: Long = 0,
    ) {
        jdbi.useHandle<Exception> { handle ->
            val status = if (wasCancelled) {
                LoadTestStatus.CANCELLED
            } else if (failedCount == completedCount + failedCount) {
                LoadTestStatus.FAILED
            } else {
                LoadTestStatus.COMPLETED
            }

            // Build detailed metrics map for JSONB column
            val metricsMap = mapOf(
                "total_duration_ms" to metrics.totalDurationMs,
                "avg_ms" to metrics.avgResponseTimeMs,
                "min_ms" to metrics.minResponseTimeMs,
                "max_ms" to metrics.maxResponseTimeMs,
                "p50_ms" to metrics.p50ResponseTimeMs,
                "p95_ms" to metrics.p95ResponseTimeMs,
                "p99_ms" to metrics.p99ResponseTimeMs,
                "rps" to metrics.requestsPerSecond,
                "success_rate_percent" to metrics.successRatePercent,
                "avg_queue_wait_ms" to avgQueueWaitMs,
                "p95_queue_wait_ms" to p95QueueWaitMs,
            )

            handle.createUpdate(
                """
                UPDATE load_test_runs
                SET status = :status,
                    batch_id = :batchId,
                    completed_at = NOW(),
                    completed_count = :completedCount,
                    failed_count = :failedCount,
                    total_duration_ms = :totalDurationMs,
                    avg_response_time_ms = :avgResponseTimeMs,
                    min_response_time_ms = :minResponseTimeMs,
                    max_response_time_ms = :maxResponseTimeMs,
                    p50_response_time_ms = :p50ResponseTimeMs,
                    p95_response_time_ms = :p95ResponseTimeMs,
                    p99_response_time_ms = :p99ResponseTimeMs,
                    requests_per_second = :requestsPerSecond,
                    success_rate_percent = :successRatePercent,
                    error_summary = :errorSummary::jsonb,
                    metrics = :metrics::jsonb
                WHERE id = :runId
                """,
            )
                .bind("runId", runId)
                .bind("batchId", batchId)
                .bind("status", status.name)
                .bind("completedCount", completedCount)
                .bind("failedCount", failedCount)
                .bind("totalDurationMs", metrics.totalDurationMs)
                .bind("avgResponseTimeMs", metrics.avgResponseTimeMs)
                .bind("minResponseTimeMs", metrics.minResponseTimeMs)
                .bind("maxResponseTimeMs", metrics.maxResponseTimeMs)
                .bind("p50ResponseTimeMs", metrics.p50ResponseTimeMs)
                .bind("p95ResponseTimeMs", metrics.p95ResponseTimeMs)
                .bind("p99ResponseTimeMs", metrics.p99ResponseTimeMs)
                .bind("requestsPerSecond", metrics.requestsPerSecond)
                .bind("successRatePercent", metrics.successRatePercent)
                .bind("errorSummary", objectMapper.writeValueAsString(metrics.errorSummary))
                .bind("metrics", objectMapper.writeValueAsString(metricsMap))
                .execute()
        }

        val finalStatus = if (wasCancelled) {
            LoadTestStatus.CANCELLED
        } else if (failedCount == completedCount + failedCount) {
            LoadTestStatus.FAILED
        } else {
            LoadTestStatus.COMPLETED
        }

        logger.info("Finalized load test run {} with status {}", runId, finalStatus)
    }

    /**
     * Internal data class for tracking request results.
     */
    private data class RequestResult(
        val id: LoadTestRequestKey,
        val sequenceNumber: Int,
        val startedAt: OffsetDateTime,
        val durationMs: Long,
        val success: Boolean,
        val errorMessage: String?,
        val errorType: String?,
        val documentId: DocumentKey?,
    )

    companion object {
        /** How often the executor polls for job completion (and stamps `last_progress_at`). */
        const val POLL_INTERVAL_MS = 500L

        /**
         * Hard cap on how long the executor waits for a batch before finalizing it.
         * After this the run leaves RUNNING (finalized), so it is no longer eligible
         * for stale-run recovery — the poller's stale timeout must stay comfortably
         * above the progress cadence, not this cap (see [LoadTestPoller]).
         */
        const val MAX_WAIT_TIME_MS = 600_000L
    }
}

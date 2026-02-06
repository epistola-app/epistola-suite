package app.epistola.suite.loadtest.batch

import app.epistola.suite.common.ids.BatchId
import app.epistola.suite.common.ids.DocumentId
import app.epistola.suite.common.ids.GenerationRequestId
import app.epistola.suite.documents.commands.BatchGenerationItem
import app.epistola.suite.documents.commands.GenerateDocumentBatch
import app.epistola.suite.loadtest.model.LoadTestMetrics
import app.epistola.suite.loadtest.model.LoadTestRequestId
import app.epistola.suite.loadtest.model.LoadTestRun
import app.epistola.suite.loadtest.model.LoadTestRunId
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
        val generationRequestId: GenerationRequestId,
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
            "Executing load test run {} - {} documents at {} concurrency",
            run.id,
            run.targetCount,
            run.concurrencyLevel,
        )

        logger.info("Submitting {} generation jobs in a single batch...", run.targetCount)

        // Build batch items
        val batchItems = (1..run.targetCount).map { sequenceNumber ->
            BatchGenerationItem(
                templateId = run.templateId,
                variantId = run.variantId,
                versionId = run.versionId,
                environmentId = run.environmentId,
                data = run.testData,
                filename = "loadtest-${run.id}-$sequenceNumber.pdf",
                correlationId = "loadtest-${run.id}-$sequenceNumber",
            )
        }

        // Submit entire batch at once
        val batchId = try {
            mediator.send(
                GenerateDocumentBatch(
                    tenantId = run.tenantId,
                    items = batchItems,
                ),
            )
        } catch (e: Exception) {
            logger.error("Failed to submit batch for load test run {}: {}", run.id, e.message)
            finalizeRun(run.id, createEmptyMetrics(), 0, run.targetCount, wasCancelled = true)
            return
        }

        logger.info("Submitted batch {} with {} requests for load test run {}", batchId.value, run.targetCount, run.id)

        // Query back the created request IDs
        val submittedJobs = queryBatchRequests(batchId, run.id)

        logger.info("Retrieved {} job IDs, waiting for async processing to complete...", submittedJobs.size)

        // Poll database to wait for all jobs to reach terminal state
        val (jobResults, wasCancelled) = pollForJobCompletion(run.id, submittedJobs)

        val endTime = System.currentTimeMillis()
        val totalDurationMs = endTime - startTime

        // Convert job results to RequestResult format
        val requestResults = jobResults.map { job ->
            RequestResult(
                id = LoadTestRequestId.generate(),
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

        logger.info(
            "Load test run {} completed: {}/{} succeeded, {} failed in {}ms",
            run.id,
            completedCount,
            run.targetCount,
            failedCount,
            totalDurationMs,
        )

        // Save detailed request data
        saveRequestResults(run.id, requestResults)

        // Calculate and save metrics
        val metrics = calculateMetrics(requestResults, totalDurationMs)
        finalizeRun(run.id, metrics, completedCount, failedCount, wasCancelled)

        // Delete load test documents
        deleteLoadTestDocuments(run.id)
    }

    /**
     * Result of an async job (from database).
     */
    private data class JobResult(
        val sequenceNumber: Int,
        val generationRequestId: GenerationRequestId,
        val createdAt: OffsetDateTime,
        val startedAt: OffsetDateTime?,
        val completedAt: OffsetDateTime?,
        val status: String,
        val errorMessage: String?,
        val documentId: DocumentId?,
    ) {
        val success: Boolean get() = status == "COMPLETED"
        val durationMs: Long get() {
            return if (startedAt != null && completedAt != null) {
                Duration.between(startedAt, completedAt).toMillis()
            } else {
                0
            }
        }
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
     * Poll database waiting for all async jobs to complete.
     * Returns results with actual timing data from the database and a cancellation flag.
     */
    private fun pollForJobCompletion(
        runId: LoadTestRunId,
        jobs: List<SubmittedJob>,
    ): Pair<List<JobResult>, Boolean> {
        val jobIds = jobs.map { it.generationRequestId }.toSet()
        val pollIntervalMs = 500L // Poll every 500ms
        val maxWaitTimeMs = 600_000L // 10 minute timeout
        val startTime = System.currentTimeMillis()
        var wasCancelled = false

        while (true) {
            // Check for cancellation
            if (isRunCancelled(runId)) {
                logger.warn("Load test run {} cancelled while waiting for jobs", runId)
                wasCancelled = true
                break
            }

            // Check timeout
            if (System.currentTimeMillis() - startTime > maxWaitTimeMs) {
                logger.error("Load test run {} timed out waiting for jobs after {}ms", runId, maxWaitTimeMs)
                break
            }

            // Query database for job statuses
            val results = jdbi.withHandle<List<JobResult>, Exception> { handle ->
                handle.createQuery(
                    """
                    SELECT
                        dgr.id,
                        dgr.created_at,
                        dgr.started_at,
                        dgr.completed_at,
                        dgr.status,
                        dgr.error_message,
                        dgr.document_id
                    FROM document_generation_requests dgr
                    WHERE dgr.id IN (<jobIds>)
                    """,
                )
                    .bindList("jobIds", jobIds.map { it.value })
                    .map { rs, _ ->
                        val requestId = GenerationRequestId.of(rs.getObject("id") as java.util.UUID)
                        val job = jobs.first { it.generationRequestId == requestId }

                        JobResult(
                            sequenceNumber = job.sequenceNumber,
                            generationRequestId = requestId,
                            createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
                            startedAt = rs.getObject("started_at", OffsetDateTime::class.java),
                            completedAt = rs.getObject("completed_at", OffsetDateTime::class.java),
                            status = rs.getString("status"),
                            errorMessage = rs.getString("error_message"),
                            documentId = rs.getObject("document_id", java.util.UUID::class.java)?.let { DocumentId.of(it) },
                        )
                    }
                    .list()
            }

            // Check if all jobs reached terminal state
            val allDone = results.all { it.status in setOf("COMPLETED", "FAILED", "CANCELLED") }
            val completed = results.count { it.status == "COMPLETED" }
            val failed = results.count { it.status == "FAILED" }
            val inProgress = results.count { it.status in setOf("PENDING", "IN_PROGRESS") }

            logger.debug(
                "Load test progress: {} completed, {} failed, {} in progress (out of {})",
                completed,
                failed,
                inProgress,
                jobIds.size,
            )

            // Update progress in database
            updateProgress(runId, completed, failed)

            if (allDone) {
                logger.info("All {} jobs completed for load test run {}", results.size, runId)
                return Pair(results, wasCancelled)
            }

            // Wait before next poll
            Thread.sleep(pollIntervalMs)
        }

        // Return partial results if cancelled or timed out
        val finalResults = jdbi.withHandle<List<JobResult>, Exception> { handle ->
            handle.createQuery(
                """
                SELECT
                    dgr.id,
                    dgr.created_at,
                    dgr.started_at,
                    dgr.completed_at,
                    dgr.status,
                    dgr.error_message,
                    dgr.document_id
                FROM document_generation_requests dgr
                WHERE dgr.id IN (<jobIds>)
                """,
            )
                .bindList("jobIds", jobIds.map { it.value })
                .map { rs, _ ->
                    val requestId = GenerationRequestId.of(rs.getObject("id") as java.util.UUID)
                    val job = jobs.first { it.generationRequestId == requestId }

                    JobResult(
                        sequenceNumber = job.sequenceNumber,
                        generationRequestId = requestId,
                        createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
                        startedAt = rs.getObject("started_at", OffsetDateTime::class.java),
                        completedAt = rs.getObject("completed_at", OffsetDateTime::class.java),
                        status = rs.getString("status"),
                        errorMessage = rs.getString("error_message"),
                        documentId = rs.getObject("document_id", java.util.UUID::class.java)?.let { DocumentId.of(it) },
                    )
                }
                .list()
        }

        return Pair(finalResults, wasCancelled)
    }

    /**
     * Update progress counts in the database.
     */
    private fun updateProgress(runId: LoadTestRunId, completedCount: Int, failedCount: Int) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                UPDATE load_test_runs
                SET completed_count = :completedCount,
                    failed_count = :failedCount
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
    private fun isRunCancelled(runId: LoadTestRunId): Boolean = jdbi.withHandle<Boolean, Exception> { handle ->
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
    private fun queryBatchRequests(batchId: BatchId, runId: LoadTestRunId): List<SubmittedJob> = jdbi.withHandle<List<SubmittedJob>, Exception> { handle ->
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
                    generationRequestId = GenerationRequestId(rs.getObject("id", java.util.UUID::class.java)),
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
     * Save detailed request results to database.
     */
    private fun saveRequestResults(runId: LoadTestRunId, results: List<RequestResult>) {
        jdbi.useTransaction<Exception> { handle ->
            val batch = handle.prepareBatch(
                """
                INSERT INTO load_test_requests (
                    id, run_id, sequence_number, started_at, completed_at, duration_ms,
                    success, error_message, error_type, document_id
                )
                VALUES (:id, :runId, :sequenceNumber, :startedAt, :completedAt, :durationMs,
                        :success, :errorMessage, :errorType, :documentId)
                """,
            )

            results.forEach { result ->
                batch
                    .bind("id", result.id)
                    .bind("runId", runId)
                    .bind("sequenceNumber", result.sequenceNumber)
                    .bind("startedAt", result.startedAt)
                    .bind("completedAt", result.startedAt.plusNanos(result.durationMs * 1_000_000))
                    .bind("durationMs", result.durationMs)
                    .bind("success", result.success)
                    .bind("errorMessage", result.errorMessage)
                    .bind("errorType", result.errorType)
                    .bind("documentId", result.documentId)
                    .add()
            }

            batch.execute()
        }

        logger.info("Saved {} request results for load test run {}", results.size, runId)
    }

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
        runId: LoadTestRunId,
        metrics: LoadTestMetrics,
        completedCount: Int,
        failedCount: Int,
        wasCancelled: Boolean,
    ) {
        jdbi.useHandle<Exception> { handle ->
            val status = if (wasCancelled) {
                LoadTestStatus.CANCELLED
            } else if (failedCount == completedCount + failedCount) {
                LoadTestStatus.FAILED
            } else {
                LoadTestStatus.COMPLETED
            }

            handle.createUpdate(
                """
                UPDATE load_test_runs
                SET status = :status,
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
                    error_summary = :errorSummary::jsonb
                WHERE id = :runId
                """,
            )
                .bind("runId", runId)
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
                .execute()
        }

        logger.info("Finalized load test run {} with status {}", runId, wasCancelled)
    }

    /**
     * Delete all documents generated during this load test.
     */
    private fun deleteLoadTestDocuments(runId: LoadTestRunId) {
        val deleted = jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate(
                """
                DELETE FROM documents
                WHERE correlation_id LIKE :pattern
                """,
            )
                .bind("pattern", "loadtest-$runId-%")
                .execute()
        }

        logger.info("Deleted {} load test documents for run {}", deleted, runId)
    }

    /**
     * Classify exception into error type for metrics.
     */
    private fun classifyError(exception: Exception): String = when {
        exception.message?.contains("validation", ignoreCase = true) == true -> "VALIDATION"
        exception.message?.contains("timeout", ignoreCase = true) == true -> "TIMEOUT"
        exception.message?.contains("not found", ignoreCase = true) == true -> "CONFIGURATION"
        else -> "GENERATION"
    }

    /**
     * Internal data class for tracking request results.
     */
    private data class RequestResult(
        val id: LoadTestRequestId,
        val sequenceNumber: Int,
        val startedAt: OffsetDateTime,
        val durationMs: Long,
        val success: Boolean,
        val errorMessage: String?,
        val errorType: String?,
        val documentId: DocumentId?,
    )
}

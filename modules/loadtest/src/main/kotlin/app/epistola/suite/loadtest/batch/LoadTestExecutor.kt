package app.epistola.suite.loadtest.batch

import app.epistola.suite.common.ids.DocumentId
import app.epistola.suite.documents.commands.GenerateDocument
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
import java.time.OffsetDateTime
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Executes load test runs by generating documents concurrently using virtual threads.
 *
 * Unlike DocumentGenerationExecutor, this does NOT use a semaphore to limit concurrency.
 * Load tests intentionally stress the system with high concurrency (up to 500 concurrent requests).
 */
@Component
class LoadTestExecutor(
    private val jdbi: Jdbi,
    private val mediator: Mediator,
    private val objectMapper: ObjectMapper,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val executor = Executors.newVirtualThreadPerTaskExecutor()

    /**
     * Execute a load test run.
     *
     * Generates [targetCount] documents with [concurrencyLevel] concurrent requests.
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

        // Track cancellation state and progress
        val cancelled = AtomicBoolean(false)
        val completedCount = AtomicInteger(0)
        val failedCount = AtomicInteger(0)

        // Store per-request results for metrics calculation
        val requestResults = mutableListOf<RequestResult>()
        val resultsLock = Any()

        // Generate N documents concurrently
        val futures = (1..run.targetCount).map { sequenceNumber ->
            CompletableFuture.supplyAsync({
                if (cancelled.get()) {
                    return@supplyAsync null
                }

                // Check for cancellation before processing each request
                if (isRunCancelled(run.id)) {
                    cancelled.set(true)
                    return@supplyAsync null
                }

                val requestId = LoadTestRequestId.generate()
                val requestStartTime = OffsetDateTime.now()
                val startMillis = System.currentTimeMillis()

                try {
                    // Generate document using existing command
                    val generationRequest = mediator.send(
                        GenerateDocument(
                            tenantId = run.tenantId,
                            templateId = run.templateId,
                            variantId = run.variantId,
                            versionId = run.versionId,
                            environmentId = run.environmentId,
                            data = run.testData,
                            filename = "loadtest-${run.id}-$sequenceNumber.pdf",
                            correlationId = "loadtest-${run.id}-$sequenceNumber",
                        ),
                    )

                    val endMillis = System.currentTimeMillis()
                    val durationMs = endMillis - startMillis

                    // Wait for document to be generated and get document ID
                    // In async mode, this returns immediately with PENDING status
                    // We'll need to poll or use a different approach
                    val documentId = waitForDocumentGeneration(generationRequest.id)

                    val result = RequestResult(
                        id = requestId,
                        sequenceNumber = sequenceNumber,
                        startedAt = requestStartTime,
                        durationMs = durationMs,
                        success = true,
                        errorMessage = null,
                        errorType = null,
                        documentId = documentId,
                    )

                    synchronized(resultsLock) {
                        requestResults.add(result)
                    }
                    completedCount.incrementAndGet()
                    result
                } catch (e: Exception) {
                    val endMillis = System.currentTimeMillis()
                    val durationMs = endMillis - startMillis

                    logger.error("Load test request {} failed: {}", requestId, e.message)

                    val result = RequestResult(
                        id = requestId,
                        sequenceNumber = sequenceNumber,
                        startedAt = requestStartTime,
                        durationMs = durationMs,
                        success = false,
                        errorMessage = e.message ?: "Unknown error",
                        errorType = classifyError(e),
                        documentId = null,
                    )

                    synchronized(resultsLock) {
                        requestResults.add(result)
                    }
                    failedCount.incrementAndGet()
                    result
                }
            }, executor)
        }

        // Wait for all requests to complete
        CompletableFuture.allOf(*futures.toTypedArray()).join()

        val endTime = System.currentTimeMillis()
        val totalDurationMs = endTime - startTime

        logger.info(
            "Load test run {} completed: {}/{} succeeded, {} failed in {}ms",
            run.id,
            completedCount.get(),
            run.targetCount,
            failedCount.get(),
            totalDurationMs,
        )

        // Save detailed request data
        saveRequestResults(run.id, requestResults)

        // Calculate and save metrics
        val metrics = calculateMetrics(requestResults, totalDurationMs)
        finalizeRun(run.id, metrics, completedCount.get(), failedCount.get(), cancelled.get())

        // Delete load test documents
        deleteLoadTestDocuments(run.id)
    }

    /**
     * Wait for document generation to complete and return document ID.
     * This is a simplified implementation for MVP.
     */
    private fun waitForDocumentGeneration(requestId: app.epistola.suite.common.ids.GenerationRequestId): DocumentId? {
        // TODO: Implement proper waiting/polling mechanism
        // For now, we'll just return null - the document generation happens asynchronously
        return null
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

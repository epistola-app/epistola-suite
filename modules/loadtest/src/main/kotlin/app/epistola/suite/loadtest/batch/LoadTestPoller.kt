package app.epistola.suite.loadtest.batch

import app.epistola.suite.loadtest.model.LoadTestRun
import app.epistola.suite.loadtest.model.LoadTestRunId
import app.epistola.suite.loadtest.model.LoadTestStatus
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.net.InetAddress
import java.time.OffsetDateTime
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Scheduled poller that claims and executes pending load test runs.
 *
 * Uses polling with `SELECT FOR UPDATE SKIP LOCKED` for safe multi-instance distribution.
 * Load tests are executed on virtual threads to avoid blocking the scheduler.
 */
@Component
@ConditionalOnProperty(
    name = ["epistola.loadtest.polling.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class LoadTestPoller(
    private val jdbi: Jdbi,
    private val loadTestExecutor: LoadTestExecutor,
    @Value("\${epistola.loadtest.polling.max-concurrent-tests:1}")
    private val maxConcurrentTests: Int,
    @Value("\${epistola.loadtest.polling.stale-timeout-minutes:10}")
    private val staleTimeoutMinutes: Int,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val instanceId = "${InetAddress.getLocalHost().hostName}-${ProcessHandle.current().pid()}"
    private val activeTests = AtomicInteger(0)
    private val executor = Executors.newVirtualThreadPerTaskExecutor()

    /**
     * Poll for pending load tests and claim one if capacity allows.
     * Runs on a fixed delay to ensure continuous polling without overlapping.
     */
    @Scheduled(fixedDelayString = "\${epistola.loadtest.polling.interval-ms:5000}")
    fun poll() {
        if (activeTests.get() >= maxConcurrentTests) {
            logger.debug("Max concurrent tests reached ({}), skipping poll", maxConcurrentTests)
            return
        }

        // First, recover any stale RUNNING tests
        recoverStaleTests()

        val run = claimNextPendingRun()
        if (run != null) {
            activeTests.incrementAndGet()
            logger.info("Claimed load test run {} (active tests: {})", run.id, activeTests.get())

            // Execute on virtual thread, don't block the scheduler
            executor.submit {
                try {
                    loadTestExecutor.execute(run)
                } catch (e: Exception) {
                    logger.error("Load test execution failed for run {}: {}", run.id, e.message, e)
                    markRunFailed(run.id, e.message)
                } finally {
                    activeTests.decrementAndGet()
                }
            }
        }
    }

    /**
     * Claim the next pending load test run using SELECT FOR UPDATE SKIP LOCKED.
     * This ensures only one instance can claim a run even under concurrent polling.
     */
    private fun claimNextPendingRun(): LoadTestRun? = jdbi.inTransaction<LoadTestRun?, Exception> { handle ->
        // PostgreSQL: Use CTE to select and update atomically
        handle.createQuery(
            """
            WITH claimed AS (
                SELECT id FROM load_test_runs
                WHERE status = 'PENDING'
                ORDER BY created_at
                LIMIT 1
                FOR UPDATE SKIP LOCKED
            )
            UPDATE load_test_runs
            SET status = :runningStatus,
                claimed_by = :instanceId,
                claimed_at = NOW(),
                started_at = NOW()
            FROM claimed
            WHERE load_test_runs.id = claimed.id
            RETURNING load_test_runs.id, tenant_id, template_id, variant_id, version_id, environment_id,
                      target_count, concurrency_level, test_data, status, claimed_by, claimed_at,
                      completed_count, failed_count, total_duration_ms, avg_response_time_ms,
                      min_response_time_ms, max_response_time_ms, p50_response_time_ms,
                      p95_response_time_ms, p99_response_time_ms, requests_per_second,
                      success_rate_percent, error_summary, created_at, started_at, completed_at
            """,
        )
            .bind("runningStatus", LoadTestStatus.RUNNING.name)
            .bind("instanceId", instanceId)
            .mapTo<LoadTestRun>()
            .findOne()
            .orElse(null)
    }

    /**
     * Mark a load test run as failed.
     */
    private fun markRunFailed(runId: LoadTestRunId, errorMessage: String?) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                UPDATE load_test_runs
                SET status = :failedStatus,
                    completed_at = NOW()
                WHERE id = :runId
                """,
            )
                .bind("runId", runId)
                .bind("failedStatus", LoadTestStatus.FAILED.name)
                .execute()
        }
    }

    /**
     * Recover stale RUNNING tests that have been abandoned by crashed instances.
     * A test is considered stale if it's been RUNNING for longer than the timeout.
     */
    private fun recoverStaleTests() {
        val recovered = jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate(
                """
                UPDATE load_test_runs
                SET status = :pendingStatus,
                    claimed_by = NULL,
                    claimed_at = NULL
                WHERE status = :runningStatus
                  AND claimed_at < :staleThreshold
                """,
            )
                .bind("pendingStatus", LoadTestStatus.PENDING.name)
                .bind("runningStatus", LoadTestStatus.RUNNING.name)
                .bind("staleThreshold", OffsetDateTime.now().minusMinutes(staleTimeoutMinutes.toLong()))
                .execute()
        }

        if (recovered > 0) {
            logger.warn("Recovered {} stale load test runs", recovered)
        }
    }
}

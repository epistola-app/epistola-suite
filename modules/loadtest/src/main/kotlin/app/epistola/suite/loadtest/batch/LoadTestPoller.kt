package app.epistola.suite.loadtest.batch

import app.epistola.suite.cluster.schedules.ClusterScheduledTask
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskDefinition
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskExecutionScope
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskHandler
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskSchedule
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.loadtest.model.LoadTestRun
import app.epistola.suite.loadtest.model.LoadTestRunKey
import app.epistola.suite.loadtest.model.LoadTestStatus
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.MediatorContext
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.PlatformRole
import app.epistola.suite.security.TenantRole
import app.epistola.suite.time.EpistolaClock
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.stereotype.Component
import java.net.InetAddress
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
    private val mediator: Mediator,
    @Value("\${epistola.loadtest.polling.max-concurrent-tests:1}")
    private val maxConcurrentTests: Int,
    @Value("\${epistola.loadtest.polling.stale-timeout-minutes:10}")
    private val staleTimeoutMinutes: Int,
    @Value("\${epistola.loadtest.polling.interval-ms:5000}")
    private val intervalMs: Long,
) : ClusterScheduledTaskHandler {
    private val logger = LoggerFactory.getLogger(javaClass)
    override val taskType: String = TASK_TYPE
    private val instanceId = "${InetAddress.getLocalHost().hostName}-${ProcessHandle.current().pid()}"
    private val activeTests = AtomicInteger(0)
    private val executor = Executors.newVirtualThreadPerTaskExecutor()

    init {
        validateStaleTimeoutMinutes(staleTimeoutMinutes)
    }

    @Bean
    fun loadTestPollerScheduledTaskDefinition(): ClusterScheduledTaskDefinition = ClusterScheduledTaskDefinition(
        taskKey = TASK_KEY,
        routingKey = ROUTING_KEY,
        taskType = TASK_TYPE,
        schedule = ClusterScheduledTaskSchedule.FixedDelay(intervalMs),
        executionScope = ClusterScheduledTaskExecutionScope.EACH_CAPABLE_NODE,
    )

    override fun handle(task: ClusterScheduledTask) {
        poll()
    }

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
            executor.submit(
                MediatorContext.runnable(mediator, SYSTEM_PRINCIPAL) {
                    try {
                        loadTestExecutor.execute(run)
                    } catch (e: Exception) {
                        logger.error("Load test execution failed for run {}: {}", run.id, e.message, e)
                        markRunFailed(run.id, e.message)
                    } finally {
                        activeTests.decrementAndGet()
                    }
                },
            )
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
                started_at = NOW(),
                last_progress_at = NOW()
            FROM claimed
            WHERE load_test_runs.id = claimed.id
            RETURNING load_test_runs.id, tenant_key, catalog_key, template_key, variant_key, version_key, environment_key,
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
    private fun markRunFailed(runId: LoadTestRunKey, errorMessage: String?) {
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
     * Recover RUNNING tests genuinely abandoned by a crashed instance.
     *
     * Staleness keys off the progress heartbeat (`last_progress_at`), not claim age:
     * a run whose executor is alive keeps stamping progress every ~500ms, so a
     * healthy long run is never recovered regardless of total duration. Only a run
     * with no progress for the timeout — a dead executor — is reset to PENDING for
     * another node to pick up. `COALESCE(last_progress_at, claimed_at)` covers the
     * pre-migration case and the sliver between claim and first stamp. See #725.
     */
    internal fun recoverStaleTests() {
        val recovered = jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate(
                """
                UPDATE load_test_runs
                SET status = :pendingStatus,
                    claimed_by = NULL,
                    claimed_at = NULL
                WHERE status = :runningStatus
                  AND COALESCE(last_progress_at, claimed_at) < :staleThreshold
                """,
            )
                .bind("pendingStatus", LoadTestStatus.PENDING.name)
                .bind("runningStatus", LoadTestStatus.RUNNING.name)
                .bind("staleThreshold", EpistolaClock.offsetDateTime().minusMinutes(staleTimeoutMinutes.toLong()))
                .execute()
        }

        if (recovered > 0) {
            logger.warn("Recovered {} stale load test runs (no progress within {} min)", recovered, staleTimeoutMinutes)
        }
    }

    companion object {
        const val TASK_KEY = "loadtest.poller"
        const val ROUTING_KEY = "system:loadtest.poller"
        const val TASK_TYPE = "loadtest.poller"

        /**
         * Guards against a stale timeout configured into the danger zone. Recovery
         * keys off the progress heartbeat, which the executor stamps every
         * [LoadTestExecutor.POLL_INTERVAL_MS]; the timeout must sit comfortably above
         * that cadence so a briefly-paused-but-alive executor is never falsely
         * recovered (re-submitting a whole second batch — #725). One minute is already
         * ~120x the poll interval; reject anything below it.
         */
        internal fun validateStaleTimeoutMinutes(staleTimeoutMinutes: Int) {
            require(staleTimeoutMinutes >= 1) {
                "epistola.loadtest.polling.stale-timeout-minutes must be >= 1 (progress heartbeat cadence is " +
                    "${LoadTestExecutor.POLL_INTERVAL_MS}ms); got $staleTimeoutMinutes"
            }
        }

        /** System principal for load test execution — runs with full access. */
        private val SYSTEM_PRINCIPAL = EpistolaPrincipal(
            userId = UserKey.of(java.util.UUID.nameUUIDFromBytes("loadtest@epistola.app".toByteArray())),
            externalId = "loadtest",
            email = "loadtest@epistola.app",
            displayName = "Load Test System",
            tenantMemberships = emptyMap(),
            globalRoles = TenantRole.entries.toSet(),
            platformRoles = PlatformRole.entries.toSet(),
            currentTenantId = null,
        )
    }
}

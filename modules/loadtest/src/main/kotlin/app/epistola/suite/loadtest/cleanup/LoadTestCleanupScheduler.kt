package app.epistola.suite.loadtest.cleanup

import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/**
 * Scheduled cleanup jobs for load test data.
 *
 * This component runs periodic cleanup tasks to remove:
 * - Old load test runs (aggregated metrics older than retention period)
 *
 * Note: Request details are stored in document_generation_requests and cleaned up
 * automatically via partition dropping (managed by PartitionMaintenanceScheduler).
 * Cleanup is scheduled via cron expression (default: 3 AM daily).
 */
@Component
@EnableScheduling
class LoadTestCleanupScheduler(
    private val jdbi: Jdbi,
    @Value("\${epistola.loadtest.cleanup.runs-retention-days:90}")
    private val runsRetentionDays: Int,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Clean up old load test runs.
     *
     * Deletes load_test_runs records older than the retention period.
     * Only deletes completed, failed, or cancelled runs.
     * Running or pending runs are never deleted.
     *
     * Note: load_test_runs is NOT partitioned (low volume aggregate data).
     */
    @Scheduled(cron = "\${epistola.loadtest.cleanup.cron:0 0 3 * * ?}")
    fun cleanupOldRuns() {
        logger.info("Starting cleanup of old load test runs...")

        try {
            val cutoffDate = OffsetDateTime.now().minusDays(runsRetentionDays.toLong())

            val deleted = jdbi.withHandle<Int, Exception> { handle ->
                handle.createUpdate(
                    """
                    DELETE FROM load_test_runs
                    WHERE created_at < :cutoffDate
                      AND status IN ('COMPLETED', 'FAILED', 'CANCELLED')
                    """,
                )
                    .bind("cutoffDate", cutoffDate)
                    .execute()
            }

            logger.info(
                "Cleaned up {} old load test runs (older than {} days)",
                deleted,
                runsRetentionDays,
            )
        } catch (e: Exception) {
            logger.error("Failed to cleanup old load test runs: {}", e.message, e)
        }
    }
}

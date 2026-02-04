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
 * - Old request details (load_test_requests older than retention period)
 *
 * Note: load_test_runs (aggregated metrics) are retained indefinitely.
 * Cleanup is scheduled via cron expression (default: 3 AM daily).
 */
@Component
@EnableScheduling
class LoadTestCleanupScheduler(
    private val jdbi: Jdbi,
    @Value("\${epistola.loadtest.cleanup.requests-retention-days:7}")
    private val requestsRetentionDays: Int,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Clean up old load test request details.
     *
     * Deletes load_test_requests records older than the retention period.
     * The aggregated metrics in load_test_runs are retained indefinitely.
     */
    @Scheduled(cron = "\${epistola.loadtest.cleanup.cron:0 0 3 * * ?}")
    fun cleanupOldRequests() {
        logger.info("Starting cleanup of old load test request details...")

        try {
            val cutoffDate = OffsetDateTime.now().minusDays(requestsRetentionDays.toLong())

            val deleted = jdbi.withHandle<Int, Exception> { handle ->
                handle.createUpdate(
                    """
                    DELETE FROM load_test_requests ltr
                    USING load_test_runs ltr_run
                    WHERE ltr.run_id = ltr_run.id
                      AND ltr_run.created_at < :cutoffDate
                    """,
                )
                    .bind("cutoffDate", cutoffDate)
                    .execute()
            }

            logger.info(
                "Cleaned up {} old load test request records (older than {} days)",
                deleted,
                requestsRetentionDays,
            )
        } catch (e: Exception) {
            logger.error("Failed to cleanup old load test requests: {}", e.message, e)
        }
    }
}

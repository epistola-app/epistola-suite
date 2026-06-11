package app.epistola.suite.loadtest.cleanup

import app.epistola.suite.cluster.schedules.ClusterScheduledTask
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskDefinition
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskExecutionScope
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskHandler
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskSchedule
import app.epistola.suite.time.EpistolaClock
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.stereotype.Component

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
class LoadTestCleanupScheduler(
    private val jdbi: Jdbi,
    @Value("\${epistola.loadtest.cleanup.runs-retention-days:90}")
    private val runsRetentionDays: Int,
    @Value("\${epistola.loadtest.cleanup.cron:0 0 3 * * ?}")
    private val cron: String,
) : ClusterScheduledTaskHandler {

    private val logger = LoggerFactory.getLogger(javaClass)
    override val taskType: String = TASK_TYPE

    @Bean
    fun loadTestCleanupScheduledTaskDefinition(): ClusterScheduledTaskDefinition = ClusterScheduledTaskDefinition(
        taskKey = TASK_KEY,
        routingKey = ROUTING_KEY,
        taskType = TASK_TYPE,
        schedule = ClusterScheduledTaskSchedule.Cron(cron),
        executionScope = ClusterScheduledTaskExecutionScope.SINGLE_OWNER,
    )

    override fun handle(task: ClusterScheduledTask) {
        cleanupOldRuns()
    }

    fun cleanupOldRuns() {
        logger.info("Starting cleanup of old load test runs...")

        try {
            val cutoffDate = EpistolaClock.offsetDateTime().minusDays(runsRetentionDays.toLong())

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

    companion object {
        const val TASK_KEY = "loadtest.cleanup"
        const val ROUTING_KEY = "system:loadtest.cleanup"
        const val TASK_TYPE = "loadtest.cleanup"
    }
}

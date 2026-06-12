package app.epistola.suite.logs

import app.epistola.suite.cluster.schedules.ClusterScheduledTask
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskDefinition
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskExecutionScope
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskHandler
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskSchedule
import app.epistola.suite.observability.recordScheduledTask
import app.epistola.suite.time.EpistolaClock
import io.micrometer.core.instrument.MeterRegistry
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.stereotype.Component

/**
 * Prunes `application_log` to the configured retention window (default 7 days).
 *
 * Registered as a `SINGLE_OWNER` cluster scheduled task, so exactly one capable
 * node runs each due occurrence — multi-pod safe without any extra advisory lock
 * (there is no startup/every-node path here, unlike `PartitionMaintenanceScheduler`).
 *
 * The plain `DELETE` is cheap because `idx_application_log_occurred` covers the
 * `occurred_at` predicate and weekly volume is expected to be low. If volume ever
 * grows enough to make the delete expensive, convert the table to daily RANGE
 * partitioning and drop partitions instead.
 */
@Component
@ConditionalOnProperty(prefix = "epistola.logs", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class ApplicationLogRetentionScheduler(
    private val jdbi: Jdbi,
    private val meterRegistry: MeterRegistry,
    private val properties: ApplicationLogProperties,
) : ClusterScheduledTaskHandler {
    private val logger = LoggerFactory.getLogger(javaClass)
    override val taskType: String = TASK_TYPE

    @Bean
    fun applicationLogRetentionScheduledTaskDefinition(): ClusterScheduledTaskDefinition = ClusterScheduledTaskDefinition(
        taskKey = TASK_KEY,
        routingKey = ROUTING_KEY,
        taskType = TASK_TYPE,
        schedule = ClusterScheduledTaskSchedule.Cron(properties.retentionCron),
        executionScope = ClusterScheduledTaskExecutionScope.SINGLE_OWNER,
    )

    override fun handle(task: ClusterScheduledTask) {
        meterRegistry.recordScheduledTask("application-log-retention") {
            deleteExpired()
        }
    }

    /**
     * Delete rows older than the retention window and return how many were removed.
     * Directly invokable (and tested) without constructing a [ClusterScheduledTask].
     */
    fun deleteExpired(): Int {
        val cutoff = EpistolaClock.offsetDateTime().minusDays(properties.retentionDays)
        val deleted = jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate("DELETE FROM application_log WHERE occurred_at < :cutoff")
                .bind("cutoff", cutoff)
                .execute()
        }
        if (deleted > 0) {
            logger.info("Application log retention deleted {} row(s) older than {}", deleted, cutoff)
        } else {
            logger.debug("Application log retention found no rows older than {}", cutoff)
        }
        return deleted
    }

    companion object {
        const val TASK_KEY = "core.application-log-retention"
        const val ROUTING_KEY = "system:core.application-log-retention"
        const val TASK_TYPE = "core.application-log-retention"
    }
}

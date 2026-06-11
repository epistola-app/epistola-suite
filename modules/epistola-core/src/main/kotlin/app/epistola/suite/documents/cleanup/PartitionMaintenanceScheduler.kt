package app.epistola.suite.documents.cleanup

import app.epistola.suite.cluster.schedules.ClusterScheduledTask
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskDefinition
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskExecutionScope
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskHandler
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskSchedule
import app.epistola.suite.observability.recordScheduledTask
import app.epistola.suite.time.EpistolaClock
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PreDestroy
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Bean
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * Scheduled maintenance for RANGE-partitioned tables.
 *
 * For each configured table, this component:
 *   - Creates current + next month's partition at startup, so cross-month
 *     inserts at month boundaries always have a home (30-day buffer to detect
 *     and fix any creation failures).
 *   - Re-runs the same create/drop logic daily on a configurable cron.
 *   - Drops monthly partitions older than the table's retention window.
 *
 * Retention is per-table because the data has different value over time:
 * `documents` and `document_generation_requests` get the longer
 * `epistola.partitions.retention-months` (default 3); `generation_results`
 * is consumer-driven ephemera that gets the shorter
 * `epistola.partitions.generation-results-retention-months` (default 1).
 */
@Component
@ConditionalOnProperty(
    name = ["epistola.partitions.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class PartitionMaintenanceScheduler(
    private val jdbi: Jdbi,
    private val meterRegistry: MeterRegistry,
    @Value("\${epistola.partitions.retention-months:3}")
    private val retentionMonths: Int,
    @Value("\${epistola.partitions.generation-results-retention-months:1}")
    private val generationResultsRetentionMonths: Int,
    @Value("\${epistola.partitions.maintenance-cron:0 0 2 * * ?}")
    private val maintenanceCron: String,
) : ClusterScheduledTaskHandler {
    private val logger = LoggerFactory.getLogger(javaClass)
    override val taskType: String = TASK_TYPE

    @Volatile
    private var shuttingDown = false

    @PreDestroy
    fun shutdown() {
        logger.info("Partition maintenance scheduler shutting down")
        shuttingDown = true
    }

    private val partitionConfigs by lazy {
        listOf(
            PartitionConfig("documents", retentionMonths),
            PartitionConfig("document_generation_requests", retentionMonths),
            PartitionConfig("generation_results", generationResultsRetentionMonths),
        )
    }

    @Bean
    fun partitionMaintenanceScheduledTaskDefinition(): ClusterScheduledTaskDefinition = ClusterScheduledTaskDefinition(
        taskKey = TASK_KEY,
        routingKey = ROUTING_KEY,
        taskType = TASK_TYPE,
        schedule = ClusterScheduledTaskSchedule.Cron(maintenanceCron),
        // The recurring path is single-owner; the advisory lock below additionally
        // guards the ApplicationReadyEvent startup path, which runs on every node.
        executionScope = ClusterScheduledTaskExecutionScope.SINGLE_OWNER,
    )

    /**
     * Initialize partitions after application is ready and Flyway migrations have completed.
     * Creates current month and next month partitions to bootstrap the system.
     */
    @EventListener(ApplicationReadyEvent::class)
    fun initializePartitions() {
        logger.info("Initializing partitions after application ready (Flyway migrations completed)")
        maintainPartitions()
    }

    /**
     * Run partition maintenance.
     *
     * The recurring path is driven by `cluster_tasks_scheduled`, which gives
     * the task a stable owning node. The transaction-level advisory lock stays
     * as protection for startup initialization, which still runs on every node.
     *
     * `pg_try_advisory_xact_lock` auto-releases when the wrapping transaction
     * commits or rolls back, so we cannot leak the lock if this call dies
     * mid-flight.
     */
    override fun handle(task: ClusterScheduledTask) {
        maintainPartitions()
    }

    fun maintainPartitions() {
        if (shuttingDown) return

        meterRegistry.recordScheduledTask("partition-maintenance") {
            jdbi.useTransaction<Exception> { handle ->
                val acquired = handle.createQuery("SELECT pg_try_advisory_xact_lock(:key)")
                    .bind("key", PARTITION_MAINTENANCE_LOCK_KEY)
                    .mapTo(Boolean::class.java)
                    .one()
                if (!acquired) {
                    logger.debug("Partition maintenance skipped — another instance holds the lock")
                    return@useTransaction
                }

                logger.info("Starting partition maintenance")
                partitionConfigs.forEach { config ->
                    try {
                        createRequiredPartitions(config)
                        dropOldPartitions(config)
                    } catch (e: Exception) {
                        logger.error("Failed to maintain partitions for table {}: {}", config.tableName, e.message, e)
                    }
                }
                logger.info("Partition maintenance completed")
            }
        }
    }

    /**
     * Ensure the current and next month partitions exist for [config]. Idempotent.
     */
    private fun createRequiredPartitions(config: PartitionConfig) {
        val now = YearMonth.from(EpistolaClock.offsetDateTime())
        createPartitionForMonth(config, now)
        createPartitionForMonth(config, now.plusMonths(1))
    }

    /**
     * Create partition for a specific month if it doesn't exist.
     */
    private fun createPartitionForMonth(
        config: PartitionConfig,
        month: YearMonth,
    ) {
        val partitionName = "${config.tableName}_${month.format(DateTimeFormatter.ofPattern("yyyy_MM"))}"
        val startDate = month.atDay(1)
        val endDate = month.plusMonths(1).atDay(1)

        try {
            val exists = jdbi.withHandle<Boolean, Exception> { handle ->
                handle.createQuery(
                    """
                    SELECT EXISTS (
                        SELECT 1 FROM pg_tables
                        WHERE schemaname = 'public' AND tablename = :partitionName
                    )
                    """,
                )
                    .bind("partitionName", partitionName)
                    .mapTo(Boolean::class.java)
                    .one()
            }

            if (!exists) {
                jdbi.useHandle<Exception> { handle ->
                    // IF NOT EXISTS belt-and-suspenders: even though we hold the
                    // advisory lock, a misconfigured deploy that bypasses it
                    // (or a race during the first deploy) shouldn't crash the
                    // scheduler.
                    handle.execute(
                        """
                        CREATE TABLE IF NOT EXISTS $partitionName
                        PARTITION OF ${config.tableName}
                        FOR VALUES FROM ('$startDate') TO ('$endDate')
                        """,
                    )
                }
                logger.info("Created partition: {}", partitionName)
            } else {
                logger.debug("Partition already exists: {}", partitionName)
            }
        } catch (e: Exception) {
            // This is critical - we need the partition!
            logger.error("CRITICAL: Failed to create partition: {}", partitionName, e)
            throw e
        }
    }

    /**
     * Drop partitions older than the config's retention period.
     */
    private fun dropOldPartitions(config: PartitionConfig) {
        val cutoffMonth = YearMonth.from(EpistolaClock.offsetDateTime()).minusMonths(config.retentionMonths.toLong())
        val cutoffPartition = "${config.tableName}_${cutoffMonth.format(DateTimeFormatter.ofPattern("yyyy_MM"))}"

        // Query for partitions older than retention period
        val oldPartitions = jdbi.withHandle<List<String>, Exception> { handle ->
            handle.createQuery(
                """
                SELECT tablename
                FROM pg_tables
                WHERE schemaname = 'public'
                  AND tablename LIKE :tablePattern
                  AND tablename < :cutoffPartition
                ORDER BY tablename
                """,
            )
                .bind("tablePattern", "${config.tableName}_%")
                .bind("cutoffPartition", cutoffPartition)
                .mapTo(String::class.java)
                .list()
        }

        // Drop old partitions
        var droppedCount = 0
        oldPartitions.forEach { partitionName ->
            try {
                jdbi.useHandle<Exception> { handle ->
                    handle.createUpdate("DROP TABLE IF EXISTS $partitionName CASCADE")
                        .execute()
                }
                droppedCount++
                logger.info("Dropped old partition: {} (older than {} months)", partitionName, config.retentionMonths)
            } catch (e: Exception) {
                logger.error("Failed to drop partition {}: {}", partitionName, e.message, e)
            }
        }

        if (droppedCount > 0) {
            logger.info("Dropped {} old partition(s) for table {}", droppedCount, config.tableName)
        } else {
            logger.debug("No old partitions to drop for table {}", config.tableName)
        }
    }

    private data class PartitionConfig(
        val tableName: String,
        val retentionMonths: Int,
    )

    companion object {
        const val TASK_KEY = "core.partition-maintenance"
        const val ROUTING_KEY = "system:core.partition-maintenance"
        const val TASK_TYPE = "core.partition-maintenance"

        // Stable bigint key for `pg_try_advisory_xact_lock`. Application-defined,
        // not derived from anything dynamic, so all instances try to acquire the
        // same lock. Picked from the high range to avoid collision with any
        // other advisory locks the project might add later — increment the suffix
        // (`_v2`) if the semantics of partition maintenance ever change in a way
        // that should release a still-running v1 lock holder mid-restart.
        private const val PARTITION_MAINTENANCE_LOCK_KEY: Long = 0x4570_5061_7274_4D31L // "EpPartM1"
    }
}

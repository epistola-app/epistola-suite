package app.epistola.suite.documents.cleanup

import app.epistola.suite.cluster.schedules.ClusterScheduledTask
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskDefinition
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskExecutionScope
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskHandler
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskSchedule
import app.epistola.suite.observability.recordScheduledTask
import app.epistola.suite.partitions.PartitionedTable
import app.epistola.suite.partitions.PartitionedTableContributor
import app.epistola.suite.time.EpistolaClock
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PreDestroy
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.stereotype.Component
import java.time.YearMonth

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
 * The set of tables is contributed by [PartitionedTableContributor] beans rather
 * than hard-coded here, so each module owns its own partitioned tables (core via
 * [CorePartitionedTables]; the audit module declares `audit_log`). Retention is
 * per-table: a `null` retention means **keep forever** (partitions created monthly
 * but never auto-dropped — e.g. the audit log; old partitions may later be
 * detached/archived to cold storage, never deleted).
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
    private val tableContributors: List<PartitionedTableContributor>,
    @Value("\${epistola.partitions.maintenance-cron:0 0 2 * * ?}")
    private val maintenanceCron: String,
) : ClusterScheduledTaskHandler,
    SmartInitializingSingleton {
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
        tableContributors.flatMap { it.partitionedTables() }
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
     * Bootstrap partitions once all singletons are instantiated (so Flyway has migrated) but
     * **before** Spring's `ApplicationRunner`s and the `ApplicationReadyEvent`. This matters because
     * startup runners can dispatch commands that write to a partitioned table (e.g. the demo loader
     * creating a tenant → an `audit_log` write); if bootstrap ran at `ApplicationReadyEvent` (after
     * runners) the current month's partition wouldn't exist yet. Creates the current + next month
     * partitions.
     */
    override fun afterSingletonsInstantiated() {
        logger.info("Initializing partitions after singletons instantiated (Flyway migrations completed)")
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
    private fun createRequiredPartitions(config: PartitionedTable) {
        val now = YearMonth.from(EpistolaClock.offsetDateTime())
        createPartitionForMonth(config, now)
        createPartitionForMonth(config, now.plusMonths(1))
    }

    /**
     * Ensure the partition covering [month] exists for [config]. Idempotent.
     *
     * The DDL runs inside the `epistola_create_partition` SECURITY DEFINER function
     * (see the `V…__core_partition_functions.sql` migration) rather than as raw
     * `CREATE TABLE`, so the runtime DB role needs no DDL privileges — only
     * `EXECUTE` on the function (issue #438). The month is computed here from
     * [EpistolaClock] and passed as a typed `date`; the function derives the
     * partition name and bounds.
     */
    private fun createPartitionForMonth(
        config: PartitionedTable,
        month: YearMonth,
    ) {
        try {
            val partitionName = jdbi.withHandle<String, Exception> { handle ->
                handle.createQuery("SELECT epistola_create_partition(:parent::regclass, :month)")
                    .bind("parent", config.tableName)
                    .bind("month", month.atDay(1))
                    .mapTo(String::class.java)
                    .one()
            }
            logger.debug("Ensured partition exists: {}", partitionName)
        } catch (e: Exception) {
            // This is critical - we need the partition!
            logger.error("CRITICAL: Failed to create partition for {} {}: {}", config.tableName, month, e.message, e)
            throw e
        }
    }

    /**
     * Drop partitions older than the config's retention period. A null retention
     * means "keep forever" (e.g. the audit log) — no partitions are ever dropped.
     *
     * The DROP runs inside the `epistola_drop_partitions_before` SECURITY DEFINER
     * function (no DDL privilege needed on the runtime role, issue #438). The
     * cutoff month is computed here from [EpistolaClock]; the function drops only
     * real child partitions of the parent older than it and returns their names.
     */
    private fun dropOldPartitions(config: PartitionedTable) {
        val retentionMonths = config.retentionMonths ?: return
        val cutoff = YearMonth.from(EpistolaClock.offsetDateTime()).minusMonths(retentionMonths.toLong()).atDay(1)

        val dropped = jdbi.withHandle<List<String>, Exception> { handle ->
            handle.createQuery("SELECT * FROM epistola_drop_partitions_before(:parent::regclass, :cutoff)")
                .bind("parent", config.tableName)
                .bind("cutoff", cutoff)
                .mapTo(String::class.java)
                .list()
        }

        if (dropped.isNotEmpty()) {
            logger.info("Dropped {} old partition(s) for table {} (older than {} months): {}", dropped.size, config.tableName, retentionMonths, dropped)
        } else {
            logger.debug("No old partitions to drop for table {}", config.tableName)
        }
    }

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
        // `internal` so the integration test can contend for the same lock
        // instead of duplicating the literal.
        internal const val PARTITION_MAINTENANCE_LOCK_KEY: Long = 0x4570_5061_7274_4D31L // "EpPartM1"
    }
}

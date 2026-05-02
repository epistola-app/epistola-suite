package app.epistola.suite.documents.cleanup

import jakarta.annotation.PreDestroy
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * Scheduled maintenance for partitioned tables.
 *
 * This component runs periodic maintenance tasks to:
 * - Create current month and next month partitions after Flyway migrations complete (bootstrap)
 * - Create next month's partition daily (1 month ahead)
 * - Drop old partitions (older than retention period)
 *
 * Runs daily at 2 AM for early failure detection (provides 30-day buffer to catch and fix issues).
 *
 * This enables instant TTL enforcement via partition dropping instead of slow DELETE operations.
 *
 * Two table shapes are supported:
 * - **Single-level** (RANGE by `created_at` only): documents,
 *   document_generation_requests. Sub-partitions are named `<table>_<YYYY>_<MM>`.
 * - **Multi-level** (LIST by partition → RANGE by `created_at`):
 *   generation_results. Sub-partitions are named `<table>_p<N>_<YYYY>_<MM>` where
 *   `N` enumerates the LIST children created in the migration. Each child has
 *   its own monthly RANGE sub-partitions and its own independent retention.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(
    name = ["epistola.partitions.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class PartitionMaintenanceScheduler(
    private val jdbi: Jdbi,
    @Value("\${epistola.partitions.retention-months:3}")
    private val retentionMonths: Int,
    @Value("\${epistola.partitions.generation-results-retention-months:1}")
    private val generationResultsRetentionMonths: Int,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var shuttingDown = false

    @PreDestroy
    fun shutdown() {
        logger.info("Partition maintenance scheduler shutting down")
        shuttingDown = true
    }

    private val partitionConfigs = listOf(
        PartitionConfig("documents", "created_at"),
        PartitionConfig("document_generation_requests", "created_at"),
    )

    private val multiLevelConfigs by lazy {
        listOf(
            MultiLevelPartitionConfig(
                tableName = "generation_results",
                partitionCount = 64, // matches Partition.TOTAL_PARTITIONS — see V26
                retentionMonths = generationResultsRetentionMonths,
            ),
        )
    }

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
     * Run partition maintenance daily at 2 AM.
     *
     * Creates current and next month's partitions and drops old partitions for all configured tables.
     * Daily execution provides early failure detection (30-day buffer to fix issues).
     */
    @Scheduled(cron = "\${epistola.partitions.maintenance-cron:0 0 2 * * ?}")
    fun maintainPartitions() {
        if (shuttingDown) return
        logger.info(
            "Starting partition maintenance (retention: {} months for documents/requests, {} months for generation_results)",
            retentionMonths,
            generationResultsRetentionMonths,
        )

        partitionConfigs.forEach { config ->
            try {
                createRequiredPartitions(config)
                dropOldPartitions(config)
            } catch (e: Exception) {
                logger.error("Failed to maintain partitions for table {}: {}", config.tableName, e.message, e)
            }
        }

        multiLevelConfigs.forEach { config ->
            try {
                createRequiredMultiLevelPartitions(config)
                dropOldMultiLevelPartitions(config)
            } catch (e: Exception) {
                logger.error("Failed to maintain multi-level partitions for table {}: {}", config.tableName, e.message, e)
            }
        }

        logger.info("Partition maintenance completed")
    }

    /**
     * Create required partitions (current month + next month) if they don't exist.
     *
     * This is called at startup and daily to ensure we always have the necessary partitions.
     * Provides 30-day buffer to detect and fix partition creation failures.
     */
    private fun createRequiredPartitions(config: PartitionConfig) {
        val now = YearMonth.now()

        // Create current month partition (needed immediately)
        createPartitionForMonth(config, now)

        // Create next month partition (buffer for month boundary)
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
                    handle.execute(
                        """
                        CREATE TABLE $partitionName
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
     * Drop partitions older than the retention period.
     */
    private fun dropOldPartitions(config: PartitionConfig) {
        val cutoffMonth = YearMonth.now().minusMonths(retentionMonths.toLong())
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
                logger.info("Dropped old partition: {} (older than {} months)", partitionName, retentionMonths)
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

    // ---- Multi-level (LIST → RANGE) maintenance ----

    /**
     * For each LIST partition number 0..(partitionCount-1), ensure its current and
     * next-month RANGE sub-partitions exist.
     */
    private fun createRequiredMultiLevelPartitions(config: MultiLevelPartitionConfig) {
        val now = YearMonth.now()
        for (p in 0 until config.partitionCount) {
            val parent = "${config.tableName}_p$p"
            createSubPartitionForMonth(parent, now)
            createSubPartitionForMonth(parent, now.plusMonths(1))
        }
    }

    /**
     * Drop monthly sub-partitions older than the multi-level config's retention,
     * across all LIST children. We over-fetch with a coarse LIKE prefix and
     * filter in Kotlin via the structural regex in [parseMonthSuffix] — using
     * LIKE alone for the full structure is fragile because `_` in LIKE matches
     * any character.
     */
    private fun dropOldMultiLevelPartitions(config: MultiLevelPartitionConfig) {
        val cutoffMonth = YearMonth.now().minusMonths(config.retentionMonths.toLong())
        val candidates = jdbi.withHandle<List<String>, Exception> { handle ->
            handle.createQuery(
                """
                SELECT tablename
                FROM pg_tables
                WHERE schemaname = 'public'
                  AND tablename LIKE :prefix
                ORDER BY tablename
                """,
            )
                .bind("prefix", "${config.tableName}_p%")
                .mapTo(String::class.java)
                .list()
        }

        // Only consider names that look like `<table>_p<N>_<YYYY>_<MM>`. This
        // skips the bare LIST-child rows (`generation_results_p0`) and any
        // non-conforming junk so we never drop a structural partition.
        val subPartitionRegex = Regex("^${Regex.escape(config.tableName)}_p\\d+_\\d{4}_\\d{2}$")

        var droppedCount = 0
        for (name in candidates) {
            if (!subPartitionRegex.matches(name)) continue
            val month = parseMonthSuffix(name) ?: continue
            if (month < cutoffMonth) {
                try {
                    jdbi.useHandle<Exception> { handle ->
                        handle.createUpdate("DROP TABLE IF EXISTS $name CASCADE").execute()
                    }
                    droppedCount++
                    logger.info(
                        "Dropped old sub-partition: {} (older than {} months)",
                        name,
                        config.retentionMonths,
                    )
                } catch (e: Exception) {
                    logger.error("Failed to drop sub-partition {}: {}", name, e.message, e)
                }
            }
        }

        if (droppedCount > 0) {
            logger.info("Dropped {} old sub-partition(s) for table {}", droppedCount, config.tableName)
        } else {
            logger.debug("No old sub-partitions to drop for table {}", config.tableName)
        }
    }

    /**
     * Create a single `<parent>_<YYYY>_<MM>` sub-partition if it doesn't exist.
     * `parent` is a LIST child like `generation_results_p3` (already itself
     * partitioned BY RANGE per V26).
     */
    private fun createSubPartitionForMonth(parent: String, month: YearMonth) {
        val name = "${parent}_${month.format(DateTimeFormatter.ofPattern("yyyy_MM"))}"
        val startDate = month.atDay(1)
        val endDate = month.plusMonths(1).atDay(1)

        try {
            val exists = jdbi.withHandle<Boolean, Exception> { handle ->
                handle.createQuery(
                    """
                    SELECT EXISTS (
                        SELECT 1 FROM pg_tables
                        WHERE schemaname = 'public' AND tablename = :name
                    )
                    """,
                )
                    .bind("name", name)
                    .mapTo(Boolean::class.java)
                    .one()
            }
            if (!exists) {
                jdbi.useHandle<Exception> { handle ->
                    handle.execute(
                        """
                        CREATE TABLE $name PARTITION OF $parent
                        FOR VALUES FROM ('$startDate') TO ('$endDate')
                        """,
                    )
                }
                logger.info("Created sub-partition: {}", name)
            } else {
                logger.debug("Sub-partition already exists: {}", name)
            }
        } catch (e: Exception) {
            logger.error("CRITICAL: Failed to create sub-partition: {}", name, e)
            throw e
        }
    }

    /**
     * Parse the trailing `_YYYY_MM` from a sub-partition name. Returns null if
     * the name doesn't match the pattern (defensive — pg_tables shouldn't return
     * non-matches given our LIKE filter, but the regex is the source of truth).
     */
    private fun parseMonthSuffix(tableName: String): YearMonth? {
        val match = Regex(".*_(\\d{4})_(\\d{2})$").find(tableName) ?: return null
        return runCatching { YearMonth.of(match.groupValues[1].toInt(), match.groupValues[2].toInt()) }.getOrNull()
    }

    private data class PartitionConfig(
        val tableName: String,
        val partitionColumn: String,
    )

    private data class MultiLevelPartitionConfig(
        val tableName: String,
        val partitionCount: Int,
        val retentionMonths: Int,
    )
}

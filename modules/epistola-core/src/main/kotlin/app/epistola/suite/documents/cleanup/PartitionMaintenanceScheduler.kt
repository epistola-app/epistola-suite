package app.epistola.suite.documents.cleanup

import jakarta.annotation.PostConstruct
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * Scheduled maintenance for partitioned tables.
 *
 * This component runs periodic maintenance tasks to:
 * - Create current month and next month partitions at startup (bootstrap)
 * - Create next month's partition daily (1 month ahead)
 * - Drop old partitions (older than retention period)
 *
 * Runs daily at 2 AM for early failure detection (provides 30-day buffer to catch and fix issues).
 *
 * This enables instant TTL enforcement via partition dropping instead of slow DELETE operations.
 *
 * Applies to:
 * - documents (partitioned by created_at)
 * - document_generation_requests (partitioned by created_at)
 * - load_test_requests (partitioned by started_at)
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
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val partitionConfigs = listOf(
        PartitionConfig("documents", "created_at"),
        PartitionConfig("document_generation_requests", "created_at"),
        PartitionConfig("load_test_requests", "started_at"),
    )

    /**
     * Initialize partitions at startup.
     * Creates current month and next month partitions to bootstrap the system.
     */
    @PostConstruct
    fun initializePartitions() {
        logger.info("Initializing partitions at startup")
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
        logger.info("Starting partition maintenance (retention: {} months)", retentionMonths)

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
                    handle.createUpdate(
                        """
                        CREATE TABLE $partitionName
                        PARTITION OF ${config.tableName}
                        FOR VALUES FROM (:startDate) TO (:endDate)
                        """,
                    )
                        .bind("startDate", startDate)
                        .bind("endDate", endDate)
                        .execute()
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

    private data class PartitionConfig(
        val tableName: String,
        val partitionColumn: String,
    )
}

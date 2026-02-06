package app.epistola.suite.documents.cleanup

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
 * - Create future partitions (6 months ahead by default)
 * - Drop old partitions (older than retention period)
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
    @Value("\${epistola.partitions.future-months:6}")
    private val futureMonths: Int,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val partitionConfigs = listOf(
        PartitionConfig("documents", "created_at"),
        PartitionConfig("document_generation_requests", "created_at"),
        PartitionConfig("load_test_requests", "started_at"),
    )

    /**
     * Run partition maintenance daily at 1 AM.
     *
     * Creates future partitions and drops old partitions for all configured tables.
     */
    @Scheduled(cron = "\${epistola.partitions.maintenance-cron:0 0 1 * * ?}")
    fun maintainPartitions() {
        logger.info("Starting partition maintenance (retention: {} months, future: {} months)", retentionMonths, futureMonths)

        partitionConfigs.forEach { config ->
            try {
                createFuturePartitions(config)
                dropOldPartitions(config)
            } catch (e: Exception) {
                logger.error("Failed to maintain partitions for table {}: {}", config.tableName, e.message, e)
            }
        }

        logger.info("Partition maintenance completed")
    }

    /**
     * Create partitions for future months if they don't exist.
     */
    private fun createFuturePartitions(config: PartitionConfig) {
        val now = YearMonth.now()
        var createdCount = 0

        (1..futureMonths).forEach { monthsAhead ->
            val month = now.plusMonths(monthsAhead.toLong())
            val partitionName = "${config.tableName}_${month.format(DateTimeFormatter.ofPattern("yyyy_MM"))}"
            val startDate = month.atDay(1)
            val endDate = month.plusMonths(1).atDay(1)

            try {
                val created = jdbi.withHandle<Boolean, Exception> { handle ->
                    // Check if partition already exists
                    val exists = handle.createQuery(
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

                    if (!exists) {
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
                        true
                    } else {
                        false
                    }
                }

                if (created) {
                    createdCount++
                    logger.info("Created partition: {}", partitionName)
                }
            } catch (e: Exception) {
                logger.error("Failed to create partition {}: {}", partitionName, e.message, e)
            }
        }

        if (createdCount > 0) {
            logger.info("Created {} new partition(s) for table {}", createdCount, config.tableName)
        } else {
            logger.debug("No new partitions needed for table {}", config.tableName)
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

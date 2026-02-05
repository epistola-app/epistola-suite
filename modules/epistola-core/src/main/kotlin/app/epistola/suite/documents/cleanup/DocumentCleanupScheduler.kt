package app.epistola.suite.documents.cleanup

import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/**
 * Scheduled cleanup jobs for document generation.
 *
 * This component runs periodic cleanup tasks to remove:
 * - Expired generation jobs (completed jobs past retention period)
 * - Old documents (documents past retention period)
 *
 * Cleanup is scheduled via cron expression (default: 2 AM daily).
 */
@Component
@EnableScheduling
class DocumentCleanupScheduler(
    private val jdbi: Jdbi,
    @Value("\${epistola.generation.jobs.retention-days:7}")
    private val jobRetentionDays: Int,
    @Value("\${epistola.generation.documents.retention-days:30}")
    private val documentRetentionDays: Int,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Clean up expired generation jobs.
     *
     * Deletes requests that have expired (expires_at < NOW()).
     * This will cascade delete generation items.
     */
    @Scheduled(cron = "\${epistola.generation.jobs.cleanup-cron:0 0 2 * * ?}")
    fun cleanupExpiredJobs() {
        logger.info("Starting cleanup of expired generation jobs...")

        try {
            val deleted = jdbi.withHandle<Int, Exception> { handle ->
                handle.createUpdate(
                    """
                    DELETE FROM document_generation_requests
                    WHERE expires_at IS NOT NULL
                      AND expires_at < :now
                      AND status IN ('COMPLETED', 'FAILED', 'CANCELLED')
                    """,
                )
                    .bind("now", OffsetDateTime.now())
                    .execute()
            }

            logger.info("Cleaned up {} expired generation jobs", deleted)
        } catch (e: Exception) {
            logger.error("Failed to cleanup expired jobs: {}", e.message, e)
        }
    }

    /**
     * Clean up old documents.
     *
     * Deletes documents created before the retention period.
     * This is independent of job cleanup - documents can be retained longer than jobs.
     */
    @Scheduled(cron = "\${epistola.generation.jobs.cleanup-cron:0 0 2 * * ?}")
    fun cleanupOldDocuments() {
        logger.info("Starting cleanup of old documents...")

        try {
            val cutoffDate = OffsetDateTime.now().minusDays(documentRetentionDays.toLong())

            val deleted = jdbi.withHandle<Int, Exception> { handle ->
                handle.createUpdate(
                    """
                    DELETE FROM documents
                    WHERE created_at < :cutoffDate
                    """,
                )
                    .bind("cutoffDate", cutoffDate)
                    .execute()
            }

            logger.info("Cleaned up {} old documents (older than {} days)", deleted, documentRetentionDays)
        } catch (e: Exception) {
            logger.error("Failed to cleanup old documents: {}", e.message, e)
        }
    }

    /**
     * Update batch counters periodically to match actual request counts.
     *
     * This is a safety mechanism to ensure batch counters are accurate.
     * In the flattened schema, each request represents one document.
     * Batches aggregate multiple requests via batch_id.
     */
    @Scheduled(fixedDelay = 300000) // Run every 5 minutes
    fun updateBatchCounters() {
        try {
            jdbi.useHandle<Exception> { handle ->
                handle.createUpdate(
                    """
                    UPDATE document_generation_batches b
                    SET
                        completed_count = (
                            SELECT COUNT(*)
                            FROM document_generation_requests r
                            WHERE r.batch_id = b.id AND r.status = 'COMPLETED'
                        ),
                        failed_count = (
                            SELECT COUNT(*)
                            FROM document_generation_requests r
                            WHERE r.batch_id = b.id AND r.status = 'FAILED'
                        ),
                        completed_at = CASE
                            WHEN (
                                SELECT COUNT(*)
                                FROM document_generation_requests r
                                WHERE r.batch_id = b.id
                                  AND r.status IN ('COMPLETED', 'FAILED')
                            ) = b.total_count
                            THEN COALESCE(b.completed_at, NOW())
                            ELSE b.completed_at
                        END
                    WHERE b.completed_at IS NULL
                       OR (
                           SELECT COUNT(*)
                           FROM document_generation_requests r
                           WHERE r.batch_id = b.id
                             AND r.status IN ('COMPLETED', 'FAILED')
                       ) < b.total_count
                    """,
                )
                    .execute()
            }
        } catch (e: Exception) {
            logger.error("Failed to update batch counters: {}", e.message, e)
        }
    }
}

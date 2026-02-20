package app.epistola.suite.documents.batch

import jakarta.annotation.PreDestroy
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Recovers stale jobs that were claimed by instances that crashed or became unresponsive.
 *
 * A job is considered stale if it has been IN_PROGRESS for longer than the configured timeout.
 * Stale jobs are reset to PENDING status so they can be claimed by another instance.
 */
@Component
@ConditionalOnProperty(
    name = ["epistola.generation.polling.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class StaleJobRecovery(
    private val jdbi: Jdbi,
    @Value("\${epistola.generation.polling.stale-timeout-minutes:10}")
    private val staleTimeoutMinutes: Long,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var shuttingDown = false

    @PreDestroy
    fun shutdown() {
        logger.info("Stale job recovery shutting down")
        shuttingDown = true
    }

    /**
     * Check for and recover stale jobs every minute.
     */
    @Scheduled(fixedRate = 60000) // Every minute
    fun recoverStaleJobs() {
        if (shuttingDown) return
        val staleInterval = "$staleTimeoutMinutes minutes"

        jdbi.useTransaction<Exception> { handle ->
            // Find stale requests (IN_PROGRESS for too long)
            val staleRequestIds = handle.createQuery(
                """
                SELECT id FROM document_generation_requests
                WHERE status = 'IN_PROGRESS'
                  AND claimed_at < NOW() - :staleInterval::interval
                """,
            )
                .bind("staleInterval", staleInterval)
                .mapTo(UUID::class.java)
                .list()

            if (staleRequestIds.isEmpty()) {
                return@useTransaction
            }

            logger.warn("Found {} stale jobs to recover: {}", staleRequestIds.size, staleRequestIds)

            // Reset requests to PENDING (in flattened schema, each request = 1 document)
            val requestsReset = handle.createUpdate(
                """
                UPDATE document_generation_requests
                SET status = 'PENDING',
                    claimed_by = NULL,
                    claimed_at = NULL,
                    started_at = NULL
                WHERE id IN (<requestIds>)
                """,
            )
                .bindList("requestIds", staleRequestIds)
                .execute()

            logger.warn("Recovered {} stale jobs (reset to PENDING)", requestsReset)
        }
    }
}

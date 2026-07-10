package app.epistola.suite.documents.batch

import app.epistola.suite.cluster.schedules.ClusterScheduledTask
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskDefinition
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskExecutionScope
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskHandler
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskSchedule
import app.epistola.suite.observability.recordScheduledTask
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PreDestroy
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Recovers stale jobs that were claimed by instances that crashed or became unresponsive.
 *
 * A job is considered stale if it has been IN_PROGRESS for longer than the configured timeout.
 * Stale jobs are reset to PENDING status so they can be claimed by another instance.
 *
 * Runs on **every capable node** ([ClusterScheduledTaskExecutionScope.EACH_CAPABLE_NODE]),
 * not a single elected owner. Document recovery is the guarantee that no generation
 * job is ever permanently orphaned, so it must not itself depend on one node staying
 * healthy: if the recovery task were single-owner and its owner wedged mid-run (holding
 * a renewed lease), stuck documents would never be recovered — the exact self-amplifying
 * failure in #723 (the orphaned jobs needed a recovery task pinned to the broken node).
 * The sweep is idempotent (reset IN_PROGRESS → PENDING) and reclaim uses
 * `FOR UPDATE SKIP LOCKED`, so running it concurrently on all nodes is harmless and
 * removes the single point of failure: while any node is healthy, stale jobs are
 * recovered within one interval regardless of how another node failed.
 */
@Component
@ConditionalOnProperty(
    name = ["epistola.generation.polling.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class StaleJobRecovery(
    private val jdbi: Jdbi,
    private val meterRegistry: MeterRegistry,
    @Value("\${epistola.generation.polling.stale-timeout-minutes:10}")
    private val staleTimeoutMinutes: Long,
) : ClusterScheduledTaskHandler {
    private val logger = LoggerFactory.getLogger(javaClass)
    override val taskType: String = TASK_TYPE

    @Volatile
    private var shuttingDown = false

    @PreDestroy
    fun shutdown() {
        logger.info("Stale job recovery shutting down")
        shuttingDown = true
    }

    @Bean
    fun staleJobRecoveryScheduledTaskDefinition(): ClusterScheduledTaskDefinition = ClusterScheduledTaskDefinition(
        taskKey = TASK_KEY,
        routingKey = ROUTING_KEY,
        taskType = TASK_TYPE,
        schedule = ClusterScheduledTaskSchedule.FixedRate(INTERVAL_MS),
        // Every node runs the idempotent sweep so document recovery never depends on a
        // single owner staying alive (see class KDoc / #723).
        executionScope = ClusterScheduledTaskExecutionScope.EACH_CAPABLE_NODE,
    )

    override fun handle(task: ClusterScheduledTask) {
        recoverStaleJobs()
    }

    fun recoverStaleJobs() {
        if (shuttingDown) return

        meterRegistry.recordScheduledTask("stale-job-recovery") {
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

    companion object {
        const val TASK_KEY = "core.stale-job-recovery"
        const val ROUTING_KEY = "system:core.stale-job-recovery"
        const val TASK_TYPE = "core.stale-job-recovery"
        const val INTERVAL_MS = 60_000L
    }
}

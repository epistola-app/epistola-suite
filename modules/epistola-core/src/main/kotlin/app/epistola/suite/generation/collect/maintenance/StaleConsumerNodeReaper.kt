package app.epistola.suite.generation.collect.maintenance

import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Daily DELETE for `consumer_node_assignments` rows whose last heartbeat is
 * older than [retentionHours]. Without this the table only ever grows: every
 * pod that ever polled — including renamed instances, decommissioned hosts,
 * CI runners — leaves a permanent ghost row. They're already excluded from
 * ring computation by [TouchConsumerNode]'s `last_seen_at > active_since`
 * filter, but they accumulate in queries and admin views.
 *
 * Runs at the configured cron (default 03:00 server-local) instead of a fixed
 * interval so the work happens during a quiet window. The DELETE itself is a
 * single bounded statement on an indexed column — fast even on large tables.
 *
 * Disable with `epistola.collect.reaper.enabled=false`. Tune retention with
 * `epistola.collect.stale-node-retention-hours` (default 24).
 */
@Component
@EnableScheduling
@ConditionalOnProperty(
    name = ["epistola.collect.reaper.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class StaleConsumerNodeReaper(
    private val jdbi: Jdbi,
    @Value("\${epistola.collect.stale-node-retention-hours:24}")
    private val retentionHours: Long,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${epistola.collect.reaper.cron:0 0 3 * * *}")
    fun reap() {
        val deleted = deleteStaleNodes()
        if (deleted > 0) {
            logger.info("Reaped {} stale consumer_node_assignments rows (retentionHours={})", deleted, retentionHours)
        } else {
            logger.debug("No stale consumer_node_assignments rows to reap (retentionHours={})", retentionHours)
        }
    }

    /**
     * Public for the integration test — lets it trigger the DELETE directly
     * without waiting for the cron, and observe the row count returned.
     */
    fun deleteStaleNodes(): Int = jdbi.withHandle<Int, Exception> { handle ->
        handle.createUpdate(
            """
            DELETE FROM consumer_node_assignments
            WHERE last_seen_at < now() - make_interval(hours => :retentionHours)
            """,
        )
            .bind("retentionHours", retentionHours.toInt())
            .execute()
    }
}

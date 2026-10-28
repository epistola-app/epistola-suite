// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.generation.collect.queries

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.generation.collect.domain.EmitsInFlight
import app.epistola.suite.generation.collect.domain.GenerationResultRow
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Fetch a page of pending generation results for a consumer.
 *
 * The handler reads the per-(tenant, consumer, partition) cursors from
 * `consumer_partition_cursors`, then asks `generation_results` for rows after
 * each cursor in the supplied [partitions] set. Returns up to [limit] rows
 * ordered by ascending sequence — callers use the largest sequence as
 * `acknowledgeUpTo` on the next poll.
 *
 * The collect endpoint orchestrates the call sequence:
 *   1. [TouchConsumerNode][app.epistola.suite.generation.collect.commands.TouchConsumerNode]
 *      → returns the consumer's current partition assignment.
 *   2. [AcknowledgeGenerationResults][app.epistola.suite.generation.collect.commands.AcknowledgeGenerationResults]
 *      (if `acknowledgeUpTo` was sent) → advances cursors.
 *   3. [FetchGenerationResults] → returns the next page using the
 *      now-advanced cursors.
 */
data class FetchGenerationResults(
    val tenantId: TenantKey,
    val consumerId: String,
    val partitions: Set<Int>,
    val limit: Int,
    /** How long the poll waits for emits in flight before it returns an empty page. */
    val inFlightWait: Duration = FetchGenerationResultsHandler.IN_FLIGHT_WAIT,
) : Query<FetchResultsPage>,
    RequiresPermission {
    override val permission get() = Permission.DOCUMENT_GENERATE
    override val tenantKey get() = tenantId

    init {
        require(limit in 1..MAX_LIMIT) { "limit must be in 1..$MAX_LIMIT, got $limit" }
        require(!inFlightWait.isNegative) { "inFlightWait must not be negative, got $inFlightWait" }
    }

    companion object {
        const val MAX_LIMIT: Int = 10000
    }
}

/**
 * One page of results from [FetchGenerationResults].
 *
 * @property rows result rows in ascending sequence order; empty when no
 *   pending results match.
 * @property hasMore true if more rows exist for the same partitions beyond
 *   what fit in this page (caller should poll again immediately).
 * @property lastSequence the largest sequence in [rows], or null when empty.
 *   This is the value to send as `acknowledgeUpTo` on the next poll.
 */
data class FetchResultsPage(
    val rows: List<GenerationResultRow>,
    val hasMore: Boolean,
    val lastSequence: Long?,
)

@Component
class FetchGenerationResultsHandler(
    private val jdbi: Jdbi,
    private val meterRegistry: MeterRegistry,
) : QueryHandler<FetchGenerationResults, FetchResultsPage> {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun handle(query: FetchGenerationResults): FetchResultsPage {
        if (query.partitions.isEmpty()) return FetchResultsPage(emptyList(), hasMore = false, lastSequence = null)

        val partitionList = query.partitions.toIntArray()
        // Read cursors and fetch rows in one transaction so a concurrent ack
        // doesn't move the cursor between the two reads (would skip rows).
        val rawRows = jdbi.inTransaction<List<GenerationResultRow>?, Exception> { handle ->
            val cursorMap = handle.createQuery(
                """
                SELECT partition, last_acked_sequence
                FROM consumer_partition_cursors
                WHERE tenant_key = :tenantKey
                  AND consumer_id = :consumerId
                  AND partition = ANY(:partitions::int[])
                """,
            )
                .bind("tenantKey", query.tenantId)
                .bind("consumerId", query.consumerId)
                .bindArray("partitions", Int::class.javaObjectType, *partitionList.toTypedArray())
                .map { rs, _ -> rs.getInt("partition") to rs.getLong("last_acked_sequence") }
                .toMap()

            // UNNEST joins per-partition cursors so PG can prune on `r.partition = c.p`.
            // Cursor defaults to 0 for any partition we haven't acked anything from yet.
            val cursorList = LongArray(partitionList.size) { cursorMap[partitionList[it]] ?: 0L }
            // Nothing handed out past every cursor: an idle poll ends here.
            val upTo = EmitsInFlight.highestIssued(handle)
            if (upTo <= (cursorList.minOrNull() ?: 0L)) return@inTransaction emptyList()
            if (!awaitEmitsInFlight(handle, query.tenantId, query.inFlightWait)) return@inTransaction null
            handle.createQuery(
                """
                WITH cursors(p, cur) AS (
                    SELECT unnest(:partitions::int[]), unnest(:cursors::bigint[])
                )
                SELECT r.sequence, r.partition, r.created_at, r.request_id, r.batch_id,
                       r.tenant_key, r.routing_key, r.status, r.document_id,
                       r.correlation_id, r.template_id, r.variant_id, r.version_id, r.release_version, r.draft_id, r.draft_label,
                       r.registry, r.namespace, r.catalog_key, r.catalog_version_id, r.filename, r.content_type, r.size_bytes, r.error, r.completed_at
                FROM generation_results r
                JOIN cursors c ON r.partition = c.p
                WHERE r.tenant_key = :tenantKey
                  AND r.sequence > c.cur
                  AND r.sequence <= :upTo
                ORDER BY r.sequence
                LIMIT :limit
                """,
            )
                .bind("tenantKey", query.tenantId)
                .bindArray("partitions", Int::class.javaObjectType, *partitionList.toTypedArray())
                .bindArray("cursors", Long::class.javaObjectType, *cursorList.toTypedArray())
                .bind("upTo", upTo)
                // Read limit + 1 so we can compute hasMore cheaply: if the over-read
                // returned an extra row, we know more exist; trim it before returning.
                .bind("limit", query.limit + 1)
                .mapTo<GenerationResultRow>()
                .list()
        }
            // Emits still in flight after the wait: deliver nothing this time, and say there is more.
            ?: return FetchResultsPage(emptyList(), hasMore = true, lastSequence = null)
        val hasMore = rawRows.size > query.limit
        val rows = if (hasMore) rawRows.dropLast(1) else rawRows
        val lastSequence = rows.lastOrNull()?.sequence
        return FetchResultsPage(rows = rows, hasMore = hasMore, lastSequence = lastSequence)
    }

    /**
     * Wait until [tenant]'s emits in flight now have committed or rolled back. They are the only ones
     * that can hold one of its sequences up to the highest one read just before; emits that start
     * later draw a higher one, and another tenant's rows are never in this feed. False when they are
     * still in flight after [wait].
     */
    private fun awaitEmitsInFlight(handle: Handle, tenant: TenantKey, wait: Duration): Boolean {
        val holders = EmitsInFlight.holders(handle, tenant)
        if (holders.isEmpty()) return true
        val started = System.nanoTime()
        val deadline = started + wait.toNanos()
        while (EmitsInFlight.anyStillInFlight(handle, tenant, holders)) {
            if (System.nanoTime() > deadline) {
                recordWait(started, OUTCOME_TIMED_OUT)
                logger.warn("Results are still being emitted after {}; this poll delivers none", wait)
                return false
            }
            Thread.sleep(IN_FLIGHT_POLL)
        }
        recordWait(started, OUTCOME_SETTLED)
        return true
    }

    /**
     * One poll that found emits in flight and waited for them (`epistola.collect.inflight.wait`):
     * its count is the polls that waited, its time how long; `outcome=timed_out` counts the polls
     * that hit their wait limit and delivered nothing.
     */
    private fun recordWait(started: Long, outcome: String) {
        Timer.builder(WAIT_METER)
            .description("Polls that waited for results still being emitted, and how long")
            .tag("outcome", outcome)
            .register(meterRegistry)
            .record(System.nanoTime() - started, TimeUnit.NANOSECONDS)
    }

    companion object {
        /** How long a poll waits for the emits in flight before it returns an empty page, by default. */
        val IN_FLIGHT_WAIT: Duration = Duration.ofSeconds(2)
        private val IN_FLIGHT_POLL: Duration = Duration.ofMillis(2)

        /** Timer of polls that waited for emits in flight, tagged `outcome`. */
        const val WAIT_METER: String = "epistola.collect.inflight.wait"
        const val OUTCOME_SETTLED: String = "settled"
        const val OUTCOME_TIMED_OUT: String = "timed_out"
    }
}

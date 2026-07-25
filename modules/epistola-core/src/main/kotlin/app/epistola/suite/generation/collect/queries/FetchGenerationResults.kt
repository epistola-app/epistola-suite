// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.generation.collect.queries

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.generation.collect.domain.GenerationResultRow
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

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
) : Query<FetchResultsPage>,
    RequiresPermission {
    override val permission get() = Permission.DOCUMENT_GENERATE
    override val tenantKey get() = tenantId

    init {
        require(limit in 1..MAX_LIMIT) { "limit must be in 1..$MAX_LIMIT, got $limit" }
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
) : QueryHandler<FetchGenerationResults, FetchResultsPage> {

    override fun handle(query: FetchGenerationResults): FetchResultsPage {
        if (query.partitions.isEmpty()) return FetchResultsPage(emptyList(), hasMore = false, lastSequence = null)

        val partitionList = query.partitions.toIntArray()
        // Read cursors and fetch rows in one transaction so a concurrent ack
        // doesn't move the cursor between the two reads (would skip rows).
        val rawRows = jdbi.inTransaction<List<GenerationResultRow>, Exception> { handle ->
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
            handle.createQuery(
                """
                WITH cursors(p, cur) AS (
                    SELECT unnest(:partitions::int[]), unnest(:cursors::bigint[])
                )
                SELECT r.sequence, r.partition, r.created_at, r.request_id, r.batch_id,
                       r.tenant_key, r.routing_key, r.status, r.document_id,
                       r.correlation_id, r.template_id, r.variant_id, r.version_id,
                       r.filename, r.content_type, r.size_bytes, r.error, r.completed_at
                FROM generation_results r
                JOIN cursors c ON r.partition = c.p
                WHERE r.tenant_key = :tenantKey
                  AND r.sequence > c.cur
                ORDER BY r.sequence
                LIMIT :limit
                """,
            )
                .bind("tenantKey", query.tenantId)
                .bindArray("partitions", Int::class.javaObjectType, *partitionList.toTypedArray())
                .bindArray("cursors", Long::class.javaObjectType, *cursorList.toTypedArray())
                // Read limit + 1 so we can compute hasMore cheaply: if the over-read
                // returned an extra row, we know more exist; trim it before returning.
                .bind("limit", query.limit + 1)
                .mapTo<GenerationResultRow>()
                .list()
        }
        val hasMore = rawRows.size > query.limit
        val rows = if (hasMore) rawRows.dropLast(1) else rawRows
        val lastSequence = rows.lastOrNull()?.sequence
        return FetchResultsPage(rows = rows, hasMore = hasMore, lastSequence = lastSequence)
    }
}

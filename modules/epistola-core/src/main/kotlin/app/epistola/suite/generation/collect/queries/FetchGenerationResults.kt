package app.epistola.suite.generation.collect.queries

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.generation.collect.domain.GenerationResultRow
import app.epistola.suite.generation.collect.persistence.ConsumerPartitionCursorRepository
import app.epistola.suite.generation.collect.persistence.GenerationResultRepository
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.springframework.stereotype.Component

/**
 * Fetch a page of pending generation results for a consumer.
 *
 * The query reads the per-(tenant, consumer, partition) cursors via
 * [ConsumerPartitionCursorRepository], then asks
 * [GenerationResultRepository.findFor] for rows after each cursor in the
 * supplied [partitions] set. Returns up to [limit] rows ordered by ascending
 * sequence — callers use the largest sequence as `acknowledgeUpTo` on the
 * next poll.
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
    private val cursors: ConsumerPartitionCursorRepository,
    private val results: GenerationResultRepository,
) : QueryHandler<FetchGenerationResults, FetchResultsPage> {

    override fun handle(query: FetchGenerationResults): FetchResultsPage {
        if (query.partitions.isEmpty()) return FetchResultsPage(emptyList(), hasMore = false, lastSequence = null)

        val cursorMap = cursors.cursorsFor(query.tenantId, query.consumerId, query.partitions)
        // Read limit + 1 so we can compute hasMore cheaply: if the over-read
        // returned an extra row, we know more exist; trim it before returning.
        val rawRows = results.findFor(
            tenantKey = query.tenantId,
            partitions = query.partitions,
            cursorByPartition = cursorMap,
            limit = query.limit + 1,
        )
        val hasMore = rawRows.size > query.limit
        val rows = if (hasMore) rawRows.dropLast(1) else rawRows
        val lastSequence = rows.lastOrNull()?.sequence
        return FetchResultsPage(rows = rows, hasMore = hasMore, lastSequence = lastSequence)
    }
}

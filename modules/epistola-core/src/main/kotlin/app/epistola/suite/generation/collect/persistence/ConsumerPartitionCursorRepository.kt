package app.epistola.suite.generation.collect.persistence

import app.epistola.suite.common.ids.TenantKey

/**
 * Per-(tenant, consumer, partition) ack cursor for `/generation/collect`.
 *
 * Internal helper. The cursor stores "the highest sequence the consumer has
 * confirmed processing" for that partition. Reads use `WHERE sequence > cursor`.
 * Default 0 means "never acked anything from this partition" — every row is
 * fair game.
 *
 * Cursors live per-partition (not per-consumer) so that when a consumer's
 * partition assignment changes (rebalance), the new owner of a partition
 * resumes from where the previous owner left off rather than re-processing
 * everything. Tenant-scoped explicitly for defense-in-depth.
 */
interface ConsumerPartitionCursorRepository {

    /**
     * Read the cursors for the given (tenant, consumer, partitions) tuples.
     * Partitions with no row default to 0 in the returned map.
     */
    fun cursorsFor(tenantKey: TenantKey, consumerId: String, partitions: Set<Int>): Map<Int, Long>

    /**
     * Advance each cursor in [advances] to the new value, but only when the
     * incoming value is strictly greater than the current value (GREATEST
     * semantics). Idempotent against replay; safe to call concurrently.
     */
    fun advance(tenantKey: TenantKey, consumerId: String, advances: Map<Int, Long>)
}

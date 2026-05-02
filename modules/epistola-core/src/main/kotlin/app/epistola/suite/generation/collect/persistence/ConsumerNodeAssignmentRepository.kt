package app.epistola.suite.generation.collect.persistence

import app.epistola.suite.common.ids.TenantKey
import java.time.OffsetDateTime

/**
 * Heartbeat + assignment storage per (tenant, consumer, node).
 *
 * Internal helper. On every `/ping` and `/generation/collect` call the command
 * layer [touch]es the row to bump `last_seen_at`; the partition-assignment
 * service then [activeNodes] queries to feed the consistent hash ring.
 *
 * Tenant-scoped explicitly for defense-in-depth: every method takes a
 * `TenantKey` and filters on it, so even a misrouted call from the auth layer
 * cannot cross tenant boundaries.
 */
interface ConsumerNodeAssignmentRepository {

    /**
     * Insert or update the (tenant, consumer, node) row, setting `last_seen_at`
     * to [now] and `partitions` to the supplied value.
     */
    fun touch(
        tenantKey: TenantKey,
        consumerId: String,
        nodeId: String,
        partitions: List<Int>,
        now: OffsetDateTime,
    )

    /**
     * All `(tenantKey, consumerId, nodeId)` rows for this consumer that have
     * heartbeated since [activeSince]. Used by the partition-assignment service
     * as the input to the consistent hash ring.
     */
    fun activeNodes(
        tenantKey: TenantKey,
        consumerId: String,
        activeSince: OffsetDateTime,
    ): List<NodeRow>

    data class NodeRow(
        val tenantKey: TenantKey,
        val consumerId: String,
        val nodeId: String,
        val partitions: List<Int>,
        val lastSeenAt: OffsetDateTime,
    )
}

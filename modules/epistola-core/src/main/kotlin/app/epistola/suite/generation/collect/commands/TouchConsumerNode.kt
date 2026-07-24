// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.generation.collect.commands

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.generation.collect.domain.Partition
import app.epistola.suite.generation.collect.domain.PartitionAssignment
import app.epistola.suite.generation.collect.ring.ConsistentHashRing
import app.epistola.suite.generation.collect.ring.NodeId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.time.EpistolaClock
import org.jdbi.v3.core.Jdbi
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Heartbeat from a consumer node + return its current partition assignment.
 *
 * Called from `/ping` (when authenticated) and on every `/generation/collect`.
 * The handler:
 *  1. Reads currently-active nodes for this `(tenantKey, consumerId)` —
 *     active meaning `last_seen_at > now - epistola.collect.idle-timeout-ms`.
 *  2. Treats this node as part of the active set (whether or not it was
 *     already there).
 *  3. Runs the active set through [ConsistentHashRing] to compute partition
 *     ownership.
 *  4. Touches `consumer_node_assignments` for `(tenantKey, consumerId, nodeId)`,
 *     storing the freshly-computed partition list and `last_seen_at = now`.
 *  5. Returns this node's [PartitionAssignment] (the `mine` field of the wire
 *     `_meta` line).
 */
data class TouchConsumerNode(
    val tenantId: TenantKey,
    val consumerId: String,
    val nodeId: String,
) : Command<PartitionAssignment>,
    RequiresPermission {
    override val permission get() = Permission.DOCUMENT_GENERATE
    override val tenantKey get() = tenantId
}

@Component
class TouchConsumerNodeHandler(
    private val jdbi: Jdbi,
    private val ring: ConsistentHashRing,
    @Value("\${epistola.collect.idle-timeout-ms:60000}")
    private val idleTimeoutMs: Long,
) : CommandHandler<TouchConsumerNode, PartitionAssignment> {

    override fun handle(command: TouchConsumerNode): PartitionAssignment {
        val now = EpistolaClock.offsetDateTime()
        val activeSince = now.minusNanos(idleTimeoutMs * 1_000_000)

        // Read other active nodes; build the union with this node so that this
        // call is idempotent — calling TouchConsumerNode for a node not yet in
        // the table still includes it in the assignment computation.
        val others = jdbi.withHandle<List<NodeId>, Exception> { handle ->
            handle.createQuery(
                """
                SELECT node_id
                FROM consumer_node_assignments
                WHERE tenant_key = :tenantKey
                  AND consumer_id = :consumerId
                  AND last_seen_at > :activeSince
                  AND node_id <> :nodeId
                """,
            )
                .bind("tenantKey", command.tenantId)
                .bind("consumerId", command.consumerId)
                .bind("activeSince", activeSince)
                .bind("nodeId", command.nodeId)
                .map { rs, _ -> NodeId(consumerId = command.consumerId, nodeId = rs.getString("node_id")) }
                .list()
        }
        val thisNode = NodeId(consumerId = command.consumerId, nodeId = command.nodeId)
        val activeSet = (others + thisNode).toSet()

        val myPartitions = ring.partitionsFor(thisNode, activeSet, Partition.TOTAL_PARTITIONS)

        // Persist the computed assignment + bump last_seen_at. Partitions are stored
        // as a JSON int array — cheap leaf-level shape, no Jackson needed.
        val partitionsJson = myPartitions.joinToString(prefix = "[", postfix = "]")
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO consumer_node_assignments (tenant_key, consumer_id, node_id, partitions, last_seen_at)
                VALUES (:tenantKey, :consumerId, :nodeId, :partitions::jsonb, :now)
                ON CONFLICT (tenant_key, consumer_id, node_id) DO UPDATE
                SET partitions   = EXCLUDED.partitions,
                    last_seen_at = EXCLUDED.last_seen_at
                """,
            )
                .bind("tenantKey", command.tenantId)
                .bind("consumerId", command.consumerId)
                .bind("nodeId", command.nodeId)
                .bind("partitions", partitionsJson)
                .bind("now", now)
                .execute()
        }

        return PartitionAssignment(
            total = Partition.TOTAL_PARTITIONS,
            mine = myPartitions,
        )
    }
}

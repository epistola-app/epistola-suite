package app.epistola.suite.generation.collect.commands

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.generation.collect.domain.Partition
import app.epistola.suite.generation.collect.domain.PartitionAssignment
import app.epistola.suite.generation.collect.persistence.ConsumerNodeAssignmentRepository
import app.epistola.suite.generation.collect.ring.ConsistentHashRing
import app.epistola.suite.generation.collect.ring.NodeId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

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
    private val nodeAssignments: ConsumerNodeAssignmentRepository,
    private val ring: ConsistentHashRing,
    @Value("\${epistola.collect.idle-timeout-ms:60000}")
    private val idleTimeoutMs: Long,
) : CommandHandler<TouchConsumerNode, PartitionAssignment> {

    override fun handle(command: TouchConsumerNode): PartitionAssignment {
        val now = OffsetDateTime.now()
        val activeSince = now.minusNanos(idleTimeoutMs * 1_000_000)

        // Read other active nodes; build the union with this node so that this
        // call is idempotent — calling TouchConsumerNode for a node not yet in
        // the table still includes it in the assignment computation.
        val others = nodeAssignments.activeNodes(command.tenantId, command.consumerId, activeSince)
            .map { NodeId(consumerId = it.consumerId, nodeId = it.nodeId) }
            .filter { it.nodeId != command.nodeId }
        val thisNode = NodeId(consumerId = command.consumerId, nodeId = command.nodeId)
        val activeSet = (others + thisNode).toSet()

        val myPartitions = ring.partitionsFor(thisNode, activeSet, Partition.TOTAL_PARTITIONS)

        // Persist the computed assignment + bump last_seen_at.
        nodeAssignments.touch(
            tenantKey = command.tenantId,
            consumerId = command.consumerId,
            nodeId = command.nodeId,
            partitions = myPartitions,
            now = now,
        )

        return PartitionAssignment(
            total = Partition.TOTAL_PARTITIONS,
            mine = myPartitions,
        )
    }
}

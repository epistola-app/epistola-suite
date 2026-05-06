package app.epistola.suite.generation.collect.ring

import app.epistola.suite.generation.collect.domain.murmur3x86_32
import java.util.SortedMap
import java.util.TreeMap

/**
 * Consistent-hash assignment of partitions to consumer nodes.
 *
 * Given a set of `(consumerId, nodeId)` pairs and a partition count, returns
 * which partitions belong to which node. Uses a virtual-node ring to keep
 * distribution even with small node counts.
 *
 * **Why a ring (vs naive even-split):** the v0.3 collect protocol lets a
 * submitting node pin its own results back to itself by computing a routing
 * key that hashes to one of its currently-assigned partitions
 * (`routingKeyToMe(...)` on the client). A naive even-split reshuffles ~1/2
 * of partitions when one node joins or leaves, breaking that affinity for
 * many in-flight results. The ring moves only ~1/N partitions per change,
 * preserving submitting-node affinity through rebalances.
 *
 * **Algorithm:**
 *  - Each `(consumerId, nodeId)` is placed on the ring at K virtual-node
 *    positions, hashed via `murmur3x86_32(consumerId + ":" + nodeId + "#" + i)`
 *    for i in 0..K-1.
 *  - Each partition `p` is hashed once via `murmur3x86_32("p:" + p)` and
 *    assigned to the first ring entry whose hash is greater-or-equal —
 *    wrapping around to the smallest ring entry when no such entry exists
 *    (the standard consistent-hashing "clockwise" rule).
 *
 * Pure function. No state, no mutability beyond the local TreeMap built per
 * `assign(...)` call.
 *
 * @property virtualNodesPerNode K — controls distribution variance. Higher K
 *   gives more even distribution at higher CPU cost. 128 is a sensible
 *   default for cluster sizes 1-64.
 */
class ConsistentHashRing(
    private val virtualNodesPerNode: Int = DEFAULT_VIRTUAL_NODES,
) {
    init {
        require(virtualNodesPerNode > 0) { "virtualNodesPerNode must be positive" }
    }

    /**
     * Compute the partition assignment for [nodes] across [totalPartitions].
     *
     * @param nodes all currently-active `(consumerId, nodeId)` pairs.
     * @param totalPartitions partition count, must be positive.
     * @return one entry per node, mapping the node to the sorted list of
     *   partitions it owns. Nodes not in the input are not in the output.
     *   If [nodes] is empty, returns an empty map (no assignment exists).
     */
    fun assign(nodes: Collection<NodeId>, totalPartitions: Int): Map<NodeId, List<Int>> {
        require(totalPartitions > 0) { "totalPartitions must be positive" }
        if (nodes.isEmpty()) return emptyMap()

        val ring: SortedMap<Int, NodeId> = TreeMap()
        for (node in nodes) {
            for (i in 0 until virtualNodesPerNode) {
                val key = "${node.consumerId}:${node.nodeId}#$i"
                val hash = murmur3x86_32(key.toByteArray(Charsets.UTF_8), seed = 0)
                // On hash collision (extremely rare with murmur3) the existing
                // entry wins. Doesn't break correctness — we simply have one
                // fewer virtual node, which marginally reduces evenness.
                ring.putIfAbsent(hash, node)
            }
        }

        val out = nodes.associateWith { mutableListOf<Int>() }
        for (p in 0 until totalPartitions) {
            val partitionHash = murmur3x86_32("p:$p".toByteArray(Charsets.UTF_8), seed = 0)
            val owner = ringOwner(ring, partitionHash)
            out.getValue(owner).add(p)
        }
        return out
    }

    /**
     * Convenience: compute [assign] and return only the partitions owned by
     * [thisNode]. Returns an empty list if [thisNode] is not in [nodes].
     */
    fun partitionsFor(thisNode: NodeId, nodes: Collection<NodeId>, totalPartitions: Int): List<Int> = assign(nodes, totalPartitions)[thisNode] ?: emptyList()

    /**
     * First ring entry whose hash is >= [partitionHash], wrapping to the
     * smallest entry if none exists. The ring is non-empty by precondition
     * (caller guarantees nodes is non-empty).
     */
    private fun ringOwner(ring: SortedMap<Int, NodeId>, partitionHash: Int): NodeId {
        val tail = ring.tailMap(partitionHash)
        return if (tail.isEmpty()) ring.values.first() else tail.values.first()
    }

    companion object {
        const val DEFAULT_VIRTUAL_NODES: Int = 128
    }
}

/** A consumer node identity — `(consumerId, nodeId)`. Equality is structural. */
data class NodeId(val consumerId: String, val nodeId: String)

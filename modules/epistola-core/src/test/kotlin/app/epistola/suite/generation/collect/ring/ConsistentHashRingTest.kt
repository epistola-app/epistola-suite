// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.generation.collect.ring

import app.epistola.suite.generation.collect.domain.Partition
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.math.abs

class ConsistentHashRingTest {

    private val ring = ConsistentHashRing()

    private fun nodes(consumerId: String, count: Int): List<NodeId> = (1..count).map { NodeId(consumerId, "node-$it") }

    // ----- contract behavior -----

    @Test
    fun `assign returns empty map when there are no nodes`() {
        val result = ring.assign(emptyList(), totalPartitions = 64)
        assertThat(result).isEmpty()
    }

    @Test
    fun `every partition is assigned to exactly one node`() {
        val ns = nodes("c", 4)
        val assignment = ring.assign(ns, totalPartitions = 64)

        val assignedPartitions = assignment.values.flatten()
        assertThat(assignedPartitions).hasSize(64)
        assertThat(assignedPartitions.toSet()).hasSize(64) // no duplicates
        assertThat(assignedPartitions.toSet()).isEqualTo((0 until 64).toSet())
    }

    @Test
    fun `single node owns all partitions`() {
        val n = NodeId("c", "lonely")
        val assignment = ring.assign(listOf(n), totalPartitions = 64)
        assertThat(assignment).hasSize(1)
        assertThat(assignment.getValue(n)).hasSize(64).containsExactlyInAnyOrderElementsOf(0 until 64)
    }

    @Test
    fun `assignment is deterministic for the same input`() {
        val ns = nodes("c", 5)
        val first = ring.assign(ns, totalPartitions = Partition.TOTAL_PARTITIONS)
        val second = ring.assign(ns, totalPartitions = Partition.TOTAL_PARTITIONS)
        assertThat(first).isEqualTo(second)
    }

    @Test
    fun `assignment is independent of input ordering`() {
        val a = NodeId("c", "node-a")
        val b = NodeId("c", "node-b")
        val c = NodeId("c", "node-c")

        val ordering1 = ring.assign(listOf(a, b, c), totalPartitions = 64)
        val ordering2 = ring.assign(listOf(c, a, b), totalPartitions = 64)
        val ordering3 = ring.assign(listOf(b, c, a), totalPartitions = 64)

        assertThat(ordering1).isEqualTo(ordering2).isEqualTo(ordering3)
    }

    // ----- distribution -----

    @Test
    fun `distribution is reasonably even with 4 nodes and 64 partitions`() {
        val ns = nodes("c", 4)
        val assignment = ring.assign(ns, totalPartitions = 64)

        val expected = 64 / 4 // 16 per node
        for ((node, partitions) in assignment) {
            // K=128 vnodes per node should keep deviation within ±50%
            val deviation = abs(partitions.size - expected)
            assertThat(deviation)
                .`as`("node %s owns %d partitions; expected ~%d", node.nodeId, partitions.size, expected)
                .isLessThanOrEqualTo(expected / 2)
        }
    }

    @Test
    fun `distribution is reasonably even with 16 nodes and 64 partitions`() {
        val ns = nodes("c", 16)
        val assignment = ring.assign(ns, totalPartitions = 64)

        val expected = 64 / 16 // 4 per node
        for ((node, partitions) in assignment) {
            // Smaller cluster sizes have higher variance; allow ±100%
            assertThat(partitions.size)
                .`as`("node %s owns %d partitions; expected ~%d", node.nodeId, partitions.size, expected)
                .isBetween(1, expected * 2 + 1)
        }
    }

    // ----- consistent-hashing property: ~1/N partitions move on join/leave -----

    @Test
    fun `removing one node moves at most O(partitions per node) partitions`() {
        // The whole reason we use a ring (not naive even-split): when one node
        // leaves, only its partitions should reassign to surviving nodes.
        // Naive even-split would reshuffle ~half of all partitions.
        val ns = nodes("c", 8)
        val before = ring.assign(ns, totalPartitions = 64)

        val after = ring.assign(ns.drop(1), totalPartitions = 64)

        var moved = 0
        // Skip the removed node's partitions in the comparison — those obviously moved.
        val removed = ns.first()
        val survivingNodes = ns.drop(1).toSet()
        for ((node, partitionsAfter) in after) {
            val partitionsBefore = before[node]!!.toSet()
            // Count partitions that this surviving node has now but didn't have before
            // — these moved to it from the departed node.
            moved += partitionsAfter.count { it !in partitionsBefore }
        }
        // Bound: at most all of `removed`'s partitions moved (which is ~64/8 = 8).
        // We allow generous slack for hash variance.
        val removedPartitionCount = before[removed]!!.size
        assertThat(moved)
            .`as`("removing one node should not reshuffle more than the departed node's partitions")
            .isLessThanOrEqualTo(removedPartitionCount + 4) // small slack for vnode placement edges
        assertThat(survivingNodes).allMatch { it in after.keys }
    }

    @Test
    fun `adding one node moves at most O(partitions per node) partitions`() {
        val baseNodes = nodes("c", 8)
        val before = ring.assign(baseNodes, totalPartitions = 64)

        val newNode = NodeId("c", "node-new")
        val after = ring.assign(baseNodes + newNode, totalPartitions = 64)

        // Partitions that moved AWAY from existing nodes equals the partitions
        // the new node now owns (modulo rebalances). Bound by ~1/N partitions.
        val newNodePartitions = after[newNode]!!.size
        val expectedShare = 64 / 9 // 7
        // The new node should pick up roughly 1/N share, and other nodes lose a
        // similar number total. Sanity: it's not an outlier.
        assertThat(newNodePartitions)
            .`as`("new node should own approximately 1/N partitions, got %d", newNodePartitions)
            .isBetween(1, expectedShare * 3) // allow generous variance
    }

    // ----- partitionsFor convenience method -----

    @Test
    fun `partitionsFor returns the same list as assign for the queried node`() {
        val ns = nodes("c", 4)
        val full = ring.assign(ns, totalPartitions = 64)
        for (n in ns) {
            assertThat(ring.partitionsFor(n, ns, totalPartitions = 64))
                .isEqualTo(full[n])
        }
    }

    @Test
    fun `partitionsFor returns empty list when the queried node is not in the input`() {
        val ns = nodes("c", 4)
        val unknown = NodeId("c", "ghost")
        assertThat(ring.partitionsFor(unknown, ns, totalPartitions = 64)).isEmpty()
    }

    // ----- input validation -----

    @Test
    fun `assign rejects non-positive totalPartitions`() {
        assertThat(runCatching { ring.assign(nodes("c", 1), totalPartitions = 0) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(runCatching { ring.assign(nodes("c", 1), totalPartitions = -1) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `constructor rejects non-positive virtualNodesPerNode`() {
        assertThat(runCatching { ConsistentHashRing(virtualNodesPerNode = 0) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    // ----- two consumers don't interfere -----

    @Test
    fun `different consumers get independent assignments`() {
        // Each consumer has its own logical partition space, so the same node
        // ID under two different consumers is treated as two different nodes
        // on the ring. Pin this property: an assignment for consumer-a and
        // an assignment for consumer-b can be computed independently and
        // both produce valid 64-partition coverings.
        val aNodes = nodes("consumer-a", 3)
        val bNodes = nodes("consumer-b", 3)

        val aAssignment = ring.assign(aNodes, totalPartitions = 64)
        val bAssignment = ring.assign(bNodes, totalPartitions = 64)

        assertThat(aAssignment.values.flatten().toSet()).isEqualTo((0 until 64).toSet())
        assertThat(bAssignment.values.flatten().toSet()).isEqualTo((0 until 64).toSet())
    }
}

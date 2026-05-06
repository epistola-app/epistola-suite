package app.epistola.suite.generation.collect.domain

/**
 * Partition assignment for one consumer node — the set of partitions whose results
 * this node should receive. Returned to clients in the `_meta` line of the
 * `/generation/collect` NDJSON response so they can compute routing keys that
 * land on their own partitions (`routingKeyToMe(...)` on the client side).
 *
 * `total` is always [Partition.TOTAL_PARTITIONS]; carrying it on the wire makes the
 * client side robust to any future change without an extra round-trip.
 */
data class PartitionAssignment(
    val total: Int,
    val mine: List<Int>,
    val hash: String = "murmur3",
) {
    companion object {
        /** A consumer with no assigned partitions (e.g. lost the rebalance). */
        fun empty(): PartitionAssignment = PartitionAssignment(total = Partition.TOTAL_PARTITIONS, mine = emptyList())
    }
}

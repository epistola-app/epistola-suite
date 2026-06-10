package app.epistola.suite.cluster

import java.time.OffsetDateTime

/**
 * Runtime view of a Suite process participating in the PostgreSQL-backed
 * cluster.
 *
 * Nodes heartbeat into `cluster_nodes` and advertise the capabilities they can
 * execute. Timer and scheduled-task pollers use this active-node view for
 * affinity and capability filtering. A single-node deployment is represented
 * as a cluster with one active node rather than a disabled clustering mode.
 */
data class ClusterNode(
    val nodeId: String,
    val capabilities: List<String>,
    val version: String?,
    val joinedAt: OffsetDateTime,
    val lastSeenAt: OffsetDateTime,
    val metadata: Map<String, Any?> = emptyMap(),
)

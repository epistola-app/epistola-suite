package app.epistola.suite.cluster

import java.time.OffsetDateTime

data class ClusterNode(
    val nodeId: String,
    val capabilities: List<String>,
    val version: String?,
    val joinedAt: OffsetDateTime,
    val lastSeenAt: OffsetDateTime,
    val metadata: Map<String, Any?> = emptyMap(),
)

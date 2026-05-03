package app.epistola.suite.generation.collect.queries

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime

/**
 * Snapshot of every API-key consumer for a tenant + the nodes currently
 * polling under each key + a coarse summary of how far each consumer has
 * acknowledged across its partitions.
 *
 * Powers the read-only `/tenants/{tenantId}/consumers` operations page.
 * One request per page render (and one per HTMX auto-refresh tick) — the
 * three reads run in a single transaction so the consumer list, the node
 * heartbeats, and the cursor aggregates all reflect the same point in time.
 */
data class ListConsumerStatus(val tenantId: TenantKey) :
    Query<ConsumerStatusReport>,
    RequiresPermission {
    override val permission get() = Permission.DOCUMENT_VIEW
    override val tenantKey get() = tenantId
}

data class ConsumerStatusReport(
    val tenantId: TenantKey,
    val tenantName: String,
    val consumers: List<ConsumerStatus>,
    val totals: ConsumerStatusTotals,
)

data class ConsumerStatusTotals(
    val consumerCount: Int,
    val activeNodeCount: Int,
    val totalNodeCount: Int,
    val mostRecentNodeActivity: OffsetDateTime?,
)

data class ConsumerStatus(
    val consumerId: String,
    val label: String,
    val keyPrefix: String,
    val authMethod: String,
    val enabled: Boolean,
    val lastUsedAt: OffsetDateTime?,
    val nodes: List<NodeStatus>,
    val cursorSummary: CursorSummary,
) {
    val activeNodeCount: Int get() = nodes.count { it.isActive }
    val hasAnyNodeEverConnected: Boolean get() = nodes.isNotEmpty()
}

data class NodeStatus(
    val nodeId: String,
    val lastSeenAt: OffsetDateTime,
    val isActive: Boolean,
    val assignedPartitions: List<Int>,
)

data class CursorSummary(
    val partitionsTracked: Int,
    val minAckedSequence: Long?,
    val maxAckedSequence: Long?,
    val lastAdvancedAt: OffsetDateTime?,
) {
    companion object {
        val EMPTY = CursorSummary(0, null, null, null)
    }
}

@Component
class ListConsumerStatusHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
    @Value("\${epistola.collect.idle-timeout-ms:60000}")
    private val idleTimeoutMs: Long,
) : QueryHandler<ListConsumerStatus, ConsumerStatusReport> {

    override fun handle(query: ListConsumerStatus): ConsumerStatusReport {
        val now = OffsetDateTime.now()
        val activeSince = now.minusNanos(idleTimeoutMs * 1_000_000)

        return jdbi.inTransaction<ConsumerStatusReport, Exception> { handle ->
            val tenantName = handle.createQuery("SELECT name FROM tenants WHERE id = :tenantKey")
                .bind("tenantKey", query.tenantId)
                .mapTo(String::class.java)
                .findOne()
                .orElse(query.tenantId.value)

            val rows = handle.createQuery(
                """
                SELECT ak.id::text          AS consumer_id,
                       ak.name              AS consumer_label,
                       ak.key_prefix        AS key_prefix,
                       ak.enabled           AS enabled,
                       ak.last_used_at      AS last_used_at,
                       cna.node_id          AS node_id,
                       cna.partitions::text AS partitions_json,
                       cna.last_seen_at     AS last_seen_at
                FROM api_keys ak
                LEFT JOIN consumer_node_assignments cna
                       ON cna.tenant_key = ak.tenant_key
                      AND cna.consumer_id = ak.id::text
                WHERE ak.tenant_key = :tenantKey
                ORDER BY ak.name, cna.last_seen_at DESC NULLS LAST
                """,
            )
                .bind("tenantKey", query.tenantId)
                .map { rs, _ ->
                    ConsumerNodeRow(
                        consumerId = rs.getString("consumer_id"),
                        label = rs.getString("consumer_label"),
                        keyPrefix = rs.getString("key_prefix"),
                        enabled = rs.getBoolean("enabled"),
                        lastUsedAt = rs.getObject("last_used_at", OffsetDateTime::class.java),
                        nodeId = rs.getString("node_id"),
                        partitionsJson = rs.getString("partitions_json"),
                        lastSeenAt = rs.getObject("last_seen_at", OffsetDateTime::class.java),
                    )
                }
                .list()

            val cursorByConsumer = handle.createQuery(
                """
                SELECT consumer_id,
                       COUNT(*)                  AS partitions_tracked,
                       MIN(last_acked_sequence)  AS min_seq,
                       MAX(last_acked_sequence)  AS max_seq,
                       MAX(updated_at)           AS last_advanced_at
                FROM consumer_partition_cursors
                WHERE tenant_key = :tenantKey
                GROUP BY consumer_id
                """,
            )
                .bind("tenantKey", query.tenantId)
                .map { rs, _ ->
                    rs.getString("consumer_id") to CursorSummary(
                        partitionsTracked = rs.getInt("partitions_tracked"),
                        minAckedSequence = rs.getLong("min_seq").takeUnless { rs.wasNull() },
                        maxAckedSequence = rs.getLong("max_seq").takeUnless { rs.wasNull() },
                        lastAdvancedAt = rs.getObject("last_advanced_at", OffsetDateTime::class.java),
                    )
                }
                .list()
                .toMap()

            buildReport(
                tenantId = query.tenantId,
                tenantName = tenantName,
                rows = rows,
                cursorByConsumer = cursorByConsumer,
                activeSince = activeSince,
            )
        }
    }

    private fun buildReport(
        tenantId: TenantKey,
        tenantName: String,
        rows: List<ConsumerNodeRow>,
        cursorByConsumer: Map<String, CursorSummary>,
        activeSince: OffsetDateTime,
    ): ConsumerStatusReport {
        val consumers = rows.groupBy { it.consumerId }
            .map { (consumerId, group) ->
                val first = group.first()
                val nodes = group
                    .filter { it.nodeId != null && it.lastSeenAt != null && it.partitionsJson != null }
                    .map {
                        NodeStatus(
                            nodeId = it.nodeId!!,
                            lastSeenAt = it.lastSeenAt!!,
                            isActive = it.lastSeenAt > activeSince,
                            assignedPartitions = parsePartitions(it.partitionsJson!!),
                        )
                    }
                ConsumerStatus(
                    consumerId = consumerId,
                    label = first.label,
                    keyPrefix = first.keyPrefix,
                    authMethod = "API key",
                    enabled = first.enabled,
                    lastUsedAt = first.lastUsedAt,
                    nodes = nodes,
                    cursorSummary = cursorByConsumer[consumerId] ?: CursorSummary.EMPTY,
                )
            }
            .sortedBy { it.label.lowercase() }

        val totalNodes = consumers.sumOf { it.nodes.size }
        val activeNodes = consumers.sumOf { it.activeNodeCount }
        val mostRecent = consumers.flatMap { it.nodes }.maxOfOrNull { it.lastSeenAt }

        return ConsumerStatusReport(
            tenantId = tenantId,
            tenantName = tenantName,
            consumers = consumers,
            totals = ConsumerStatusTotals(
                consumerCount = consumers.size,
                activeNodeCount = activeNodes,
                totalNodeCount = totalNodes,
                mostRecentNodeActivity = mostRecent,
            ),
        )
    }

    private fun parsePartitions(json: String): List<Int> = try {
        objectMapper.readValue(json, IntArray::class.java).toList()
    } catch (_: Exception) {
        emptyList()
    }

    private data class ConsumerNodeRow(
        val consumerId: String,
        val label: String,
        val keyPrefix: String,
        val enabled: Boolean,
        val lastUsedAt: OffsetDateTime?,
        val nodeId: String?,
        val partitionsJson: String?,
        val lastSeenAt: OffsetDateTime?,
    )
}

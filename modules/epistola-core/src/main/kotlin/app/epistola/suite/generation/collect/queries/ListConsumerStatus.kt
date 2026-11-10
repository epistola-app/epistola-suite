// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.generation.collect.queries

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.generation.collect.CollectProperties
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.time.EpistolaClock
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime

/**
 * Snapshot of every consumer for a tenant — each application, whose keys share
 * its cursors, and any key without one — + the nodes currently polling under
 * each + a coarse summary of how far each consumer has acknowledged across its
 * partitions.
 *
 * Powers the read-only `/tenants/{tenantId}/consumers` operations page.
 * One request per page render (and one per HTMX auto-refresh tick) — the
 * three reads run in a single transaction so the consumer list, the node
 * heartbeats, and the cursor aggregates all reflect the same point in time.
 *
 * Diagnostics, as the Operations nav item that leads here is: it shows integrations and their
 * progress, no document.
 */
data class ListConsumerStatus(val tenantId: TenantKey) :
    Query<ConsumerStatusReport>,
    RequiresPermission {
    override val permission get() = Permission.DIAGNOSTICS_VIEW
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
    /** The newest active key's prefix; null for an application without one. */
    val keyPrefix: String?,
    val authMethod: String,
    val enabled: Boolean,
    val lastUsedAt: OffsetDateTime?,
    val nodes: List<NodeStatus>,
    val cursorSummary: CursorSummary,
) {
    val activeNodeCount: Int get() = nodes.count { it.isActive }
    val totalNodeCount: Int get() = nodes.size

    /**
     * Five mutually-exclusive states the UI cares about. Computed once here so
     * the template can consume a single string instead of nesting three ternaries.
     */
    val state: String get() = when {
        !enabled -> STATE_DISABLED
        nodes.isEmpty() -> STATE_NOT_CONNECTED
        activeNodeCount == 0 -> STATE_STALE
        activeNodeCount == nodes.size -> STATE_HEALTHY
        else -> STATE_MIXED
    }

    /** Human-readable hover-tip describing [state]. */
    val stateTitle: String get() = when (state) {
        STATE_DISABLED -> "Disabled"
        STATE_NOT_CONNECTED -> "No nodes have polled yet"
        STATE_STALE -> "All nodes stale"
        STATE_HEALTHY -> "All nodes active"
        STATE_MIXED -> "Some nodes stale"
        else -> ""
    }

    /** What goes inside the activity pill. */
    val activityLabel: String get() = when (state) {
        STATE_DISABLED -> "Disabled"
        STATE_NOT_CONNECTED -> "Not connected"
        else -> "$activeNodeCount / ${nodes.size}"
    }

    companion object {
        const val STATE_DISABLED = "DISABLED"
        const val STATE_NOT_CONNECTED = "NOT_CONNECTED"
        const val STATE_STALE = "STALE"
        const val STATE_HEALTHY = "HEALTHY"
        const val STATE_MIXED = "MIXED"
    }
}

data class NodeStatus(
    val nodeId: String,
    val lastSeenAt: OffsetDateTime,
    val isActive: Boolean,
    val assignedPartitions: List<Int>,
) {
    /** Full partition list as a comma-separated string, e.g. "0, 3, 7". Used as a tooltip. */
    val partitionsTitle: String get() = assignedPartitions.joinToString(", ")

    /**
     * Display label: full list when ≤ [PARTITIONS_LABEL_HEAD], otherwise the first
     * [PARTITIONS_LABEL_HEAD] + " (+N more)". Computed in the data layer because
     * Thymeleaf's `#lists` utility has no slice/sub-list helper.
     */
    val partitionsLabel: String get() = when {
        assignedPartitions.size <= PARTITIONS_LABEL_HEAD -> assignedPartitions.joinToString(", ")

        else -> assignedPartitions.take(PARTITIONS_LABEL_HEAD).joinToString(", ") +
            " (+${assignedPartitions.size - PARTITIONS_LABEL_HEAD} more)"
    }

    companion object {
        private const val PARTITIONS_LABEL_HEAD = 6
    }
}

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
    private val collect: CollectProperties,
) : QueryHandler<ListConsumerStatus, ConsumerStatusReport> {

    override fun handle(query: ListConsumerStatus): ConsumerStatusReport {
        val now = EpistolaClock.offsetDateTime()
        val activeSince = now.minusNanos(collect.idleTimeoutMs * 1_000_000)
        // Nodes whose last heartbeat is older than this disappear from the report
        // entirely (not just from the totals). The page is for "what's currently
        // happening"; the durable record is the database itself, swept by the
        // separate StaleConsumerNodeReaper at a longer horizon.
        val visibleSince = now.minusHours(collect.showStaleWindowHours)

        return jdbi.inTransaction<ConsumerStatusReport, Exception> { handle ->
            val tenantName = handle.createQuery("SELECT name FROM tenants WHERE id = :tenantKey")
                .bind("tenantKey", query.tenantId)
                .mapTo(String::class.java)
                .findOne()
                .orElse(query.tenantId.value)

            val rows = handle.createQuery(
                """
                WITH consumers AS (
                    -- A consumer is an application: its keys share its cursors (ADR 0054). The
                    -- prefix shown is its newest active key's; it is enabled while one is usable.
                    SELECT a.id::text AS consumer_id,
                           a.name     AS consumer_label,
                           (SELECT k.key_prefix FROM api_keys k
                             WHERE k.application_id = a.id AND k.revoked_at IS NULL
                             ORDER BY k.created_at DESC LIMIT 1) AS key_prefix,
                           EXISTS (SELECT 1 FROM api_keys k
                                    WHERE k.application_id = a.id AND k.enabled AND k.revoked_at IS NULL) AS enabled,
                           (SELECT MAX(k.last_used_at) FROM api_keys k WHERE k.application_id = a.id) AS last_used_at
                    FROM applications a
                    WHERE a.tenant_key = :tenantKey
                    UNION ALL
                    -- A key without an application still collects under its own id.
                    SELECT k.id::text, k.name, k.key_prefix, k.enabled, k.last_used_at
                    FROM api_keys k
                    WHERE k.tenant_key = :tenantKey AND k.application_id IS NULL
                )
                SELECT c.consumer_id,
                       c.consumer_label,
                       c.key_prefix,
                       c.enabled,
                       c.last_used_at,
                       cna.node_id          AS node_id,
                       cna.partitions::text AS partitions_json,
                       cna.last_seen_at     AS last_seen_at
                FROM consumers c
                LEFT JOIN consumer_node_assignments cna
                       ON cna.tenant_key = :tenantKey
                      AND cna.consumer_id = c.consumer_id
                      AND cna.last_seen_at > :visibleSince
                ORDER BY c.consumer_label, cna.last_seen_at DESC NULLS LAST
                """,
            )
                .bind("tenantKey", query.tenantId)
                .bind("visibleSince", visibleSince)
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
        val keyPrefix: String?,
        val enabled: Boolean,
        val lastUsedAt: OffsetDateTime?,
        val nodeId: String?,
        val partitionsJson: String?,
        val lastSeenAt: OffsetDateTime?,
    )
}

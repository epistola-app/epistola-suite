package app.epistola.suite.generation.collect.persistence

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.generation.collect.persistence.ConsumerNodeAssignmentRepository.NodeRow
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime

@Repository
class JdbiConsumerNodeAssignmentRepository(
    private val jdbi: Jdbi,
) : ConsumerNodeAssignmentRepository {

    override fun touch(
        tenantKey: TenantKey,
        consumerId: String,
        nodeId: String,
        partitions: List<Int>,
        now: OffsetDateTime,
    ) {
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
                .bind("tenantKey", tenantKey)
                .bind("consumerId", consumerId)
                .bind("nodeId", nodeId)
                .bind("partitions", partitions.joinToString(prefix = "[", postfix = "]"))
                .bind("now", now)
                .execute()
        }
    }

    override fun activeNodes(
        tenantKey: TenantKey,
        consumerId: String,
        activeSince: OffsetDateTime,
    ): List<NodeRow> = jdbi.withHandle<List<NodeRow>, Exception> { handle ->
        handle.createQuery(
            """
            SELECT tenant_key, consumer_id, node_id, partitions::text AS partitions_json, last_seen_at
            FROM consumer_node_assignments
            WHERE tenant_key = :tenantKey
              AND consumer_id = :consumerId
              AND last_seen_at > :activeSince
            """,
        )
            .bind("tenantKey", tenantKey)
            .bind("consumerId", consumerId)
            .bind("activeSince", activeSince)
            .map { rs, _ ->
                NodeRow(
                    tenantKey = TenantKey.of(rs.getString("tenant_key")),
                    consumerId = rs.getString("consumer_id"),
                    nodeId = rs.getString("node_id"),
                    partitions = parseIntArray(rs.getString("partitions_json")),
                    lastSeenAt = rs.getObject("last_seen_at", OffsetDateTime::class.java),
                )
            }
            .list()
    }

    /**
     * Lightweight parser for the `[0,3,7]` JSON array shape we always store.
     * Avoids pulling Jackson into the persistence path for a leaf-level read.
     */
    private fun parseIntArray(json: String?): List<Int> {
        if (json.isNullOrBlank()) return emptyList()
        val trimmed = json.trim().removeSurrounding("[", "]").trim()
        if (trimmed.isEmpty()) return emptyList()
        return trimmed.split(",").map { it.trim().toInt() }
    }
}

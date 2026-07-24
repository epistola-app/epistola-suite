// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.cluster

import app.epistola.suite.observability.NodeIdentity
import app.epistola.suite.time.EpistolaClock
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.info.BuildProperties
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime

/**
 * PostgreSQL-backed view of the active Suite cluster.
 *
 * This is deliberately small: Phase 1 records node presence and capabilities
 * only. Scheduling, durable process leases, and cache fanout will use this
 * registry instead of re-discovering node identity independently.
 */
@Component
class ClusterNodeRegistry(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
    private val nodeIdentity: NodeIdentity,
    private val properties: ClusterProperties,
    private val buildProperties: BuildProperties? = null,
    /**
     * Whether this node performs PDF rendering. Folds [ClusterProperties.PDF_RENDER_CAPABILITY]
     * into the advertised capability set (default true). Set `epistola.generation.pdf-render.enabled=false`
     * to stop a `suite` node from claiming render jobs; a dedicated `apps/pdfrender` worker
     * leaves this at its default and advertises only `[pdf-render]`. The same property is the
     * pdfrender app's own on/off switch.
     */
    @Value("\${epistola.generation.pdf-render.enabled:true}")
    private val pdfRenderEnabled: Boolean = true,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val capabilitiesType = objectMapper.typeFactory.constructCollectionType(List::class.java, String::class.java)
    private val metadataType = objectMapper.typeFactory.constructMapType(Map::class.java, String::class.java, Any::class.java)

    fun heartbeat(): ClusterNode {
        val now = EpistolaClock.offsetDateTime()
        val capabilities = properties.normalizedCapabilities(pdfRenderEnabled)
        val capabilitiesJson = objectMapper.writeValueAsString(capabilities)
        val metadataJson = objectMapper.writeValueAsString(emptyMap<String, Any?>())
        val version = buildProperties?.version

        val node = jdbi.withHandle<ClusterNode, Exception> { handle ->
            handle.createQuery(
                """
                INSERT INTO cluster_nodes (node_id, capabilities, version, joined_at, last_seen_at, metadata)
                VALUES (:nodeId, :capabilities::jsonb, :version, :now, :now, :metadata::jsonb)
                ON CONFLICT (node_id) DO UPDATE
                SET capabilities = EXCLUDED.capabilities,
                    version = EXCLUDED.version,
                    last_seen_at = EXCLUDED.last_seen_at,
                    metadata = EXCLUDED.metadata
                RETURNING node_id, capabilities::text, version, joined_at, last_seen_at, metadata::text
                """,
            )
                // Liveness probe: fail fast on a degraded DB so the shared
                // maintenance thread retries next tick instead of wedging.
                .setQueryTimeout(LIVENESS_QUERY_TIMEOUT_SECONDS)
                .bind("nodeId", nodeIdentity.nodeId)
                .bind("capabilities", capabilitiesJson)
                .bind("version", version)
                .bind("now", now)
                .bind("metadata", metadataJson)
                .map { rs, _ -> mapNode(rs) }
                .one()
        }

        log.debug("Cluster node heartbeat recorded: nodeId={} capabilities={}", node.nodeId, node.capabilities)
        return node
    }

    /**
     * Stamps this node's `last_poll_completed_at` — the scheduler-liveness signal.
     *
     * Called by the scheduled-task scheduler at the end of each poll cycle, so the
     * timestamp advances only while the poll thread is making progress. A node whose
     * poll thread is wedged stops advancing it even though its heartbeat (written on
     * a separate thread) keeps `last_seen_at` fresh — which is exactly how
     * [schedulerActiveNodes] tells a wedged owner apart from a healthy one (#723).
     *
     * A no-op for a node that has not yet registered a heartbeat row (the `UPDATE`
     * simply matches nothing); the first heartbeat inserts the row.
     */
    fun recordPollCompleted() {
        val now = EpistolaClock.offsetDateTime()
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                UPDATE cluster_nodes
                SET last_poll_completed_at = :now
                WHERE node_id = :nodeId
                """,
            )
                // Same liveness-probe rationale as heartbeat: fail fast on a degraded
                // DB so the poll thread retries next tick instead of wedging.
                .setQueryTimeout(LIVENESS_QUERY_TIMEOUT_SECONDS)
                .bind("now", now)
                .bind("nodeId", nodeIdentity.nodeId)
                .execute()
        }
    }

    fun currentNode(): ClusterNode? = jdbi.withHandle<ClusterNode?, Exception> { handle ->
        handle.createQuery(
            """
            SELECT node_id, capabilities::text, version, joined_at, last_seen_at, metadata::text
            FROM cluster_nodes
            WHERE node_id = :nodeId
            """,
        )
            .bind("nodeId", nodeIdentity.nodeId)
            .map { rs, _ -> mapNode(rs) }
            .findOne()
            .orElse(null)
    }

    fun activeNodes(): List<ClusterNode> {
        val activeSince = EpistolaClock.offsetDateTime().minusNanos(properties.idleTimeoutMs * NANOS_PER_MILLI)
        return jdbi.withHandle<List<ClusterNode>, Exception> { handle ->
            handle.createQuery(
                """
                SELECT node_id, capabilities::text, version, joined_at, last_seen_at, metadata::text
                FROM cluster_nodes
                WHERE last_seen_at > :activeSince
                ORDER BY node_id
                """,
            )
                .bind("activeSince", activeSince)
                .map { rs, _ -> mapNode(rs) }
                .list()
        }
    }

    /**
     * Active nodes whose **scheduler** is also live — heartbeated within
     * `idleTimeoutMs` AND having completed a poll cycle within
     * `scheduledTasks.schedulerIdleTimeoutMs`. Used to build the single-owner
     * ownership set so a heartbeating-but-wedged node is excluded from election and
     * its due tasks are re-owned by a healthy node (#723). A node that has never
     * completed a poll (`last_poll_completed_at IS NULL`) is treated as
     * scheduler-inactive; the scheduler self-includes the current node separately so
     * a fresh single node still owns its tasks on the first cycle.
     */
    fun schedulerActiveNodes(): List<ClusterNode> {
        val now = EpistolaClock.offsetDateTime()
        val activeSince = now.minusNanos(properties.idleTimeoutMs * NANOS_PER_MILLI)
        val schedulerActiveSince = now.minusNanos(properties.scheduledTasks.schedulerIdleTimeoutMs * NANOS_PER_MILLI)
        return jdbi.withHandle<List<ClusterNode>, Exception> { handle ->
            handle.createQuery(
                """
                SELECT node_id, capabilities::text, version, joined_at, last_seen_at, metadata::text
                FROM cluster_nodes
                WHERE last_seen_at > :activeSince
                  AND last_poll_completed_at IS NOT NULL
                  AND last_poll_completed_at > :schedulerActiveSince
                ORDER BY node_id
                """,
            )
                .bind("activeSince", activeSince)
                .bind("schedulerActiveSince", schedulerActiveSince)
                .map { rs, _ -> mapNode(rs) }
                .list()
        }
    }

    fun allNodes(): List<ClusterNode> = jdbi.withHandle<List<ClusterNode>, Exception> { handle ->
        handle.createQuery(
            """
            SELECT node_id, capabilities::text, version, joined_at, last_seen_at, metadata::text
            FROM cluster_nodes
            ORDER BY last_seen_at DESC, node_id
            """,
        )
            .map { rs, _ -> mapNode(rs) }
            .list()
    }

    private fun mapNode(rs: java.sql.ResultSet): ClusterNode = ClusterNode(
        nodeId = rs.getString("node_id"),
        capabilities = readCapabilities(rs.getString("capabilities")),
        version = rs.getString("version"),
        joinedAt = rs.getObject("joined_at", OffsetDateTime::class.java),
        lastSeenAt = rs.getObject("last_seen_at", OffsetDateTime::class.java),
        metadata = readMetadata(rs.getString("metadata")),
    )

    private fun readCapabilities(json: String?): List<String> = runCatching {
        objectMapper.readValue<List<String>>(json ?: "[]", capabilitiesType)
    }.getOrDefault(emptyList())

    private fun readMetadata(json: String?): Map<String, Any?> = runCatching {
        objectMapper.readValue<Map<String, Any?>>(json ?: "{}", metadataType)
    }.getOrDefault(emptyMap())

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val LIVENESS_QUERY_TIMEOUT_SECONDS = 5
    }
}

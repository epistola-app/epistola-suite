package app.epistola.suite.cluster

import app.epistola.suite.observability.NodeIdentity
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
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
class SuiteNodeRegistry(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
    private val nodeIdentity: NodeIdentity,
    private val properties: ClusterProperties,
    private val buildProperties: BuildProperties? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val capabilitiesType = objectMapper.typeFactory.constructCollectionType(List::class.java, String::class.java)
    private val metadataType = objectMapper.typeFactory.constructMapType(Map::class.java, String::class.java, Any::class.java)

    fun heartbeat(): SuiteNode {
        val now = OffsetDateTime.now()
        val capabilities = properties.normalizedCapabilities()
        val capabilitiesJson = objectMapper.writeValueAsString(capabilities)
        val metadataJson = objectMapper.writeValueAsString(emptyMap<String, Any?>())
        val version = buildProperties?.version

        val node = jdbi.withHandle<SuiteNode, Exception> { handle ->
            handle.createQuery(
                """
                INSERT INTO suite_nodes (node_id, capabilities, version, joined_at, last_seen_at, metadata)
                VALUES (:nodeId, :capabilities::jsonb, :version, :now, :now, :metadata::jsonb)
                ON CONFLICT (node_id) DO UPDATE
                SET capabilities = EXCLUDED.capabilities,
                    version = EXCLUDED.version,
                    last_seen_at = EXCLUDED.last_seen_at,
                    metadata = EXCLUDED.metadata
                RETURNING node_id, capabilities::text, version, joined_at, last_seen_at, metadata::text
                """,
            )
                .bind("nodeId", nodeIdentity.nodeId)
                .bind("capabilities", capabilitiesJson)
                .bind("version", version)
                .bind("now", now)
                .bind("metadata", metadataJson)
                .map { rs, _ -> mapNode(rs) }
                .one()
        }

        log.debug("Suite node heartbeat recorded: nodeId={} capabilities={}", node.nodeId, node.capabilities)
        return node
    }

    fun currentNode(): SuiteNode? = jdbi.withHandle<SuiteNode?, Exception> { handle ->
        handle.createQuery(
            """
            SELECT node_id, capabilities::text, version, joined_at, last_seen_at, metadata::text
            FROM suite_nodes
            WHERE node_id = :nodeId
            """,
        )
            .bind("nodeId", nodeIdentity.nodeId)
            .map { rs, _ -> mapNode(rs) }
            .findOne()
            .orElse(null)
    }

    fun activeNodes(): List<SuiteNode> {
        val activeSince = OffsetDateTime.now().minusNanos(properties.idleTimeoutMs * NANOS_PER_MILLI)
        return jdbi.withHandle<List<SuiteNode>, Exception> { handle ->
            handle.createQuery(
                """
                SELECT node_id, capabilities::text, version, joined_at, last_seen_at, metadata::text
                FROM suite_nodes
                WHERE last_seen_at > :activeSince
                ORDER BY node_id
                """,
            )
                .bind("activeSince", activeSince)
                .map { rs, _ -> mapNode(rs) }
                .list()
        }
    }

    fun allNodes(): List<SuiteNode> = jdbi.withHandle<List<SuiteNode>, Exception> { handle ->
        handle.createQuery(
            """
            SELECT node_id, capabilities::text, version, joined_at, last_seen_at, metadata::text
            FROM suite_nodes
            ORDER BY last_seen_at DESC, node_id
            """,
        )
            .map { rs, _ -> mapNode(rs) }
            .list()
    }

    private fun mapNode(rs: java.sql.ResultSet): SuiteNode = SuiteNode(
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
    }
}

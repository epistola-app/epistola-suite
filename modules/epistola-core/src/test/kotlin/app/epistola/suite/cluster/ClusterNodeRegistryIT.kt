package app.epistola.suite.cluster

import app.epistola.suite.observability.NodeIdentity
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import java.time.OffsetDateTime

@TestPropertySource(
    properties = [
        "epistola.cluster.capabilities[0]=suite",
        "epistola.cluster.capabilities[1]=pdf-render",
        "epistola.cluster.idle-timeout-ms=30000",
    ],
)
class ClusterNodeRegistryIT : IntegrationTestBase() {

    @Autowired
    private lateinit var registry: ClusterNodeRegistry

    @Autowired
    private lateinit var nodeIdentity: NodeIdentity

    @Autowired
    private lateinit var jdbi: Jdbi

    @Test
    fun `heartbeat inserts current node with configured capabilities`() {
        deleteNode(nodeIdentity.nodeId)

        val node = registry.heartbeat()

        assertThat(node.nodeId).isEqualTo(nodeIdentity.nodeId)
        assertThat(node.capabilities).containsExactly("suite", "pdf-render")
        assertThat(node.joinedAt).isNotNull()
        assertThat(node.lastSeenAt).isNotNull()

        assertThat(registry.currentNode()?.nodeId).isEqualTo(nodeIdentity.nodeId)
    }

    @Test
    fun `second heartbeat updates last seen but preserves joined at`() {
        deleteNode(nodeIdentity.nodeId)

        val first = registry.heartbeat()
        Thread.sleep(5)
        val second = registry.heartbeat()

        assertThat(second.joinedAt).isEqualTo(first.joinedAt)
        assertThat(second.lastSeenAt).isAfter(first.lastSeenAt)
    }

    @Test
    fun `active nodes excludes stale heartbeats`() {
        deleteNode("stale-node")
        registry.heartbeat()
        insertNode("stale-node", OffsetDateTime.now().minusMinutes(2))

        val activeNodeIds = registry.activeNodes().map { it.nodeId }

        assertThat(activeNodeIds).contains(nodeIdentity.nodeId)
        assertThat(activeNodeIds).doesNotContain("stale-node")
    }

    @Test
    fun `all nodes includes stale heartbeats`() {
        deleteNode("stale-node")
        registry.heartbeat()
        insertNode("stale-node", OffsetDateTime.now().minusMinutes(2))

        val nodeIds = registry.allNodes().map { it.nodeId }

        assertThat(nodeIds).contains(nodeIdentity.nodeId, "stale-node")
    }

    private fun deleteNode(nodeId: String) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate("DELETE FROM cluster_nodes WHERE node_id = :nodeId")
                .bind("nodeId", nodeId)
                .execute()
        }
    }

    private fun insertNode(nodeId: String, lastSeenAt: OffsetDateTime) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO cluster_nodes (node_id, capabilities, joined_at, last_seen_at, metadata)
                VALUES (:nodeId, '["suite"]'::jsonb, :joinedAt, :lastSeenAt, '{}'::jsonb)
                ON CONFLICT (node_id) DO UPDATE
                SET last_seen_at = EXCLUDED.last_seen_at
                """,
            )
                .bind("nodeId", nodeId)
                .bind("joinedAt", lastSeenAt)
                .bind("lastSeenAt", lastSeenAt)
                .execute()
        }
    }
}

package app.epistola.suite.cluster

import app.epistola.suite.observability.NodeIdentity
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import java.time.Duration
import java.time.OffsetDateTime

@TestPropertySource(
    properties = [
        "epistola.cluster.capabilities[0]=suite",
        "epistola.cluster.capabilities[1]=unrelated-cap",
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
        // The render capability is folded in by default (epistola.generation.render-locally=true),
        // so a node advertising `suite` also renders — see ClusterProperties.normalizedCapabilities.
        assertThat(node.capabilities).containsExactlyInAnyOrder("suite", "unrelated-cap", "render")
        assertThat(node.joinedAt).isNotNull()
        assertThat(node.lastSeenAt).isNotNull()

        assertThat(registry.currentNode()?.nodeId).isEqualTo(nodeIdentity.nodeId)
    }

    @Test
    fun `second heartbeat updates last seen but preserves joined at`() {
        deleteNode(nodeIdentity.nodeId)

        val first = registry.heartbeat()
        testClock.advanceBy(Duration.ofMillis(5))
        val second = registry.heartbeat()

        assertThat(second.joinedAt).isEqualTo(first.joinedAt)
        assertThat(second.lastSeenAt).isAfter(first.lastSeenAt)
    }

    @Test
    fun `active nodes excludes stale heartbeats`() {
        deleteNode("stale-node")
        registry.heartbeat()
        insertNode("stale-node", now().minusMinutes(2))

        val activeNodeIds = registry.activeNodes().map { it.nodeId }

        assertThat(activeNodeIds).contains(nodeIdentity.nodeId)
        assertThat(activeNodeIds).doesNotContain("stale-node")
    }

    @Test
    fun `all nodes includes stale heartbeats`() {
        deleteNode("stale-node")
        registry.heartbeat()
        insertNode("stale-node", now().minusMinutes(2))

        val nodeIds = registry.allNodes().map { it.nodeId }

        assertThat(nodeIds).contains(nodeIdentity.nodeId, "stale-node")
    }

    @Test
    fun `scheduler active nodes require a fresh completed poll in addition to a heartbeat`() {
        deleteNode(nodeIdentity.nodeId)
        deleteNode("never-polled")
        deleteNode("poll-stale")

        // Current node: heartbeat then record a completed poll → scheduler-active.
        registry.heartbeat()
        registry.recordPollCompleted()

        // Heartbeating but its poll thread has never completed a cycle → excluded.
        insertNode("never-polled", now(), lastPollCompletedAt = null)
        // Heartbeat fresh, but its scheduler poll is stale (wedged owner) → excluded.
        insertNode("poll-stale", now(), lastPollCompletedAt = now().minusMinutes(2))

        val schedulerActiveIds = registry.schedulerActiveNodes().map { it.nodeId }

        assertThat(schedulerActiveIds).contains(nodeIdentity.nodeId)
        assertThat(schedulerActiveIds).doesNotContain("never-polled", "poll-stale")
        // Both excluded nodes are still plain-active by heartbeat — proving it is the
        // scheduler-liveness signal, not the heartbeat, doing the exclusion (#723).
        assertThat(registry.activeNodes().map { it.nodeId })
            .contains(nodeIdentity.nodeId, "never-polled", "poll-stale")
    }

    @Test
    fun `record poll completed is a no-op before the first heartbeat`() {
        deleteNode(nodeIdentity.nodeId)

        // No row yet → the UPDATE matches nothing and must not fail.
        registry.recordPollCompleted()

        assertThat(registry.schedulerActiveNodes().map { it.nodeId }).doesNotContain(nodeIdentity.nodeId)
    }

    private fun deleteNode(nodeId: String) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate("DELETE FROM cluster_nodes WHERE node_id = :nodeId")
                .bind("nodeId", nodeId)
                .execute()
        }
    }

    private fun insertNode(nodeId: String, lastSeenAt: OffsetDateTime, lastPollCompletedAt: OffsetDateTime? = null) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO cluster_nodes (node_id, capabilities, joined_at, last_seen_at, last_poll_completed_at, metadata)
                VALUES (:nodeId, '["suite"]'::jsonb, :joinedAt, :lastSeenAt, :lastPollCompletedAt, '{}'::jsonb)
                ON CONFLICT (node_id) DO UPDATE
                SET last_seen_at = EXCLUDED.last_seen_at,
                    last_poll_completed_at = EXCLUDED.last_poll_completed_at
                """,
            )
                .bind("nodeId", nodeId)
                .bind("joinedAt", lastSeenAt)
                .bind("lastSeenAt", lastSeenAt)
                .bind("lastPollCompletedAt", lastPollCompletedAt)
                .execute()
        }
    }

    private fun now(): OffsetDateTime = OffsetDateTime.now(testClock)
}

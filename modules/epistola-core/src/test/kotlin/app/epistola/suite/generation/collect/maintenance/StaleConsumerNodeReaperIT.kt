package app.epistola.suite.generation.collect.maintenance

import app.epistola.suite.cluster.ClusterScheduledTaskRegistry
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import java.time.OffsetDateTime
import java.util.UUID

@TestPropertySource(properties = ["epistola.collect.stale-node-retention-hours=24"])
class StaleConsumerNodeReaperIT : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    @Autowired
    private lateinit var reaper: StaleConsumerNodeReaper

    @Autowired
    private lateinit var scheduledTaskRegistry: ClusterScheduledTaskRegistry

    private fun seedNode(tenantKey: TenantKey, nodeId: String, lastSeenAt: OffsetDateTime) {
        val consumerId = UUID.randomUUID().toString()
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO consumer_node_assignments (tenant_key, consumer_id, node_id, partitions, last_seen_at)
                VALUES (:tenantKey, :consumerId, :nodeId, '[]'::jsonb, :lastSeenAt)
                """,
            )
                .bind("tenantKey", tenantKey)
                .bind("consumerId", consumerId)
                .bind("nodeId", nodeId)
                .bind("lastSeenAt", lastSeenAt)
                .execute()
        }
    }

    private fun countNodes(tenantKey: TenantKey): Int = jdbi.withHandle<Int, Exception> { handle ->
        handle.createQuery(
            "SELECT COUNT(*) FROM consumer_node_assignments WHERE tenant_key = :tenantKey",
        )
            .bind("tenantKey", tenantKey)
            .mapTo(Int::class.java)
            .one()
    }

    @Test
    fun `registers a clustered scheduled task definition`() {
        val task = scheduledTaskRegistry.find(StaleConsumerNodeReaper.TASK_KEY)

        assertThat(task).isNotNull
        assertThat(task?.routingKey).isEqualTo(StaleConsumerNodeReaper.ROUTING_KEY)
        assertThat(task?.taskType).isEqualTo(StaleConsumerNodeReaper.TASK_TYPE)
        assertThat(task?.cronExpression).isEqualTo("0 0 3 * * *")
    }

    @Test
    fun `deletes rows older than retention window and keeps fresher rows`() {
        val tenant = createTenant("Reaper Test").id
        val now = OffsetDateTime.now()

        // Two rows that should survive: one fresh, one just inside the window.
        seedNode(tenant, "fresh", now.minusSeconds(10))
        seedNode(tenant, "edge-of-window", now.minusHours(23))
        // Two rows that should die: one just past the window, one ancient.
        seedNode(tenant, "just-stale", now.minusHours(25))
        seedNode(tenant, "ancient", now.minusDays(30))

        assertThat(countNodes(tenant)).isEqualTo(4)

        val deleted = reaper.deleteStaleNodes()

        assertThat(deleted).isGreaterThanOrEqualTo(2) // ≥ 2 because other tests may have left rows on other tenants
        assertThat(countNodes(tenant)).isEqualTo(2)
    }

    @Test
    fun `is a no-op when no rows are old enough`() {
        val tenant = createTenant("Reaper No-op Test").id
        val now = OffsetDateTime.now()

        seedNode(tenant, "young-1", now.minusMinutes(5))
        seedNode(tenant, "young-2", now.minusHours(2))

        // We can't assert "deleted == 0" globally because parallel tests may leave their own
        // stale rows; assert tenant-scoped invariant instead.
        reaper.deleteStaleNodes()

        assertThat(countNodes(tenant)).isEqualTo(2)
    }
}

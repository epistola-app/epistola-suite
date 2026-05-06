package app.epistola.suite.generation.collect.queries

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.OffsetDateTime
import java.util.UUID

class ListConsumerStatusHandlerIT : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    /** Seeds an api_keys row directly so the test stays focused on the read path. */
    private fun seedApiKey(
        tenantKey: TenantKey,
        name: String,
        enabled: Boolean = true,
        lastUsedAt: OffsetDateTime? = null,
    ): UUID {
        val id = UUID.randomUUID()
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO api_keys (id, tenant_key, name, key_hash, key_prefix, enabled, last_used_at)
                VALUES (:id, :tenantKey, :name, :keyHash, :keyPrefix, :enabled, :lastUsedAt)
                """,
            )
                .bind("id", id)
                .bind("tenantKey", tenantKey)
                .bind("name", name)
                .bind("keyHash", "hash-${UUID.randomUUID()}")
                .bind("keyPrefix", "epk_${id.toString().take(8)}")
                .bind("enabled", enabled)
                .bind("lastUsedAt", lastUsedAt)
                .execute()
        }
        return id
    }

    private fun seedNode(
        tenantKey: TenantKey,
        consumerId: UUID,
        nodeId: String,
        partitions: List<Int>,
        lastSeenAt: OffsetDateTime,
    ) {
        val partitionsJson = partitions.joinToString(prefix = "[", postfix = "]")
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO consumer_node_assignments (tenant_key, consumer_id, node_id, partitions, last_seen_at)
                VALUES (:tenantKey, :consumerId, :nodeId, :partitions::jsonb, :lastSeenAt)
                """,
            )
                .bind("tenantKey", tenantKey)
                .bind("consumerId", consumerId.toString())
                .bind("nodeId", nodeId)
                .bind("partitions", partitionsJson)
                .bind("lastSeenAt", lastSeenAt)
                .execute()
        }
    }

    private fun seedCursor(
        tenantKey: TenantKey,
        consumerId: UUID,
        partition: Int,
        lastAckedSequence: Long,
        updatedAt: OffsetDateTime = OffsetDateTime.now(),
    ) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO consumer_partition_cursors
                       (tenant_key, consumer_id, partition, last_acked_sequence, updated_at)
                VALUES (:tenantKey, :consumerId, :partition, :seq, :updatedAt)
                """,
            )
                .bind("tenantKey", tenantKey)
                .bind("consumerId", consumerId.toString())
                .bind("partition", partition)
                .bind("seq", lastAckedSequence)
                .bind("updatedAt", updatedAt)
                .execute()
        }
    }

    @Test
    fun `returns empty consumer list and zero totals for a tenant with no api keys`() {
        val tenant = createTenant("Empty Tenant")

        val report = withMediator { ListConsumerStatus(tenant.id).query() }

        assertThat(report.tenantId).isEqualTo(tenant.id)
        assertThat(report.tenantName).isEqualTo("Empty Tenant")
        assertThat(report.consumers).isEmpty()
        assertThat(report.totals.consumerCount).isZero
        assertThat(report.totals.activeNodeCount).isZero
        assertThat(report.totals.totalNodeCount).isZero
        assertThat(report.totals.mostRecentNodeActivity).isNull()
    }

    @Test
    fun `lists api keys with no nodes as connected-yet-zero rather than hidden`() {
        val tenant = createTenant("Solo Tenant")
        seedApiKey(tenant.id, "Plugin")

        val report = withMediator { ListConsumerStatus(tenant.id).query() }

        assertThat(report.consumers).hasSize(1)
        with(report.consumers.single()) {
            assertThat(label).isEqualTo("Plugin")
            assertThat(authMethod).isEqualTo("API key")
            assertThat(enabled).isTrue
            assertThat(nodes).isEmpty()
            assertThat(cursorSummary.partitionsTracked).isZero
            assertThat(cursorSummary.minAckedSequence).isNull()
        }
        assertThat(report.totals.consumerCount).isEqualTo(1)
        assertThat(report.totals.totalNodeCount).isZero
    }

    @Test
    fun `groups multiple nodes under the same consumer and surfaces partitions + cursor summary`() {
        val tenant = createTenant("Multi Node Tenant")
        val now = OffsetDateTime.now()
        val consumerId = seedApiKey(tenant.id, "Production Plugin", lastUsedAt = now)
        seedNode(tenant.id, consumerId, "node-a", listOf(0, 5, 17), now.minusSeconds(2))
        seedNode(tenant.id, consumerId, "node-b", listOf(11, 23, 42), now.minusSeconds(5))
        seedCursor(tenant.id, consumerId, partition = 0, lastAckedSequence = 100)
        seedCursor(tenant.id, consumerId, partition = 5, lastAckedSequence = 250)
        seedCursor(tenant.id, consumerId, partition = 11, lastAckedSequence = 175)

        val report = withMediator { ListConsumerStatus(tenant.id).query() }

        assertThat(report.consumers).hasSize(1)
        val consumer = report.consumers.single()
        assertThat(consumer.label).isEqualTo("Production Plugin")
        assertThat(consumer.nodes).hasSize(2)
        assertThat(consumer.nodes.map { it.nodeId }).containsExactlyInAnyOrder("node-a", "node-b")
        assertThat(consumer.nodes.first { it.nodeId == "node-a" }.assignedPartitions)
            .containsExactly(0, 5, 17)
        assertThat(consumer.nodes).allSatisfy { assertThat(it.isActive).isTrue }
        assertThat(consumer.activeNodeCount).isEqualTo(2)

        assertThat(consumer.cursorSummary.partitionsTracked).isEqualTo(3)
        assertThat(consumer.cursorSummary.minAckedSequence).isEqualTo(100)
        assertThat(consumer.cursorSummary.maxAckedSequence).isEqualTo(250)
        assertThat(consumer.cursorSummary.lastAdvancedAt).isNotNull

        assertThat(report.totals.consumerCount).isEqualTo(1)
        assertThat(report.totals.activeNodeCount).isEqualTo(2)
        assertThat(report.totals.totalNodeCount).isEqualTo(2)
    }

    @Test
    fun `marks nodes whose last_seen_at is older than the idle window as stale`() {
        val tenant = createTenant("Staleness Tenant")
        val consumerId = seedApiKey(tenant.id, "Mixed Plugin")
        val now = OffsetDateTime.now()
        seedNode(tenant.id, consumerId, "fresh", listOf(1), now.minusSeconds(5))
        // Default idle-timeout-ms is 60_000; 10 minutes is comfortably past it.
        seedNode(tenant.id, consumerId, "stale", listOf(2), now.minusMinutes(10))

        val report = withMediator { ListConsumerStatus(tenant.id).query() }

        val consumer = report.consumers.single()
        val byId = consumer.nodes.associateBy { it.nodeId }
        assertThat(byId.getValue("fresh").isActive).isTrue
        assertThat(byId.getValue("stale").isActive).isFalse
        assertThat(consumer.activeNodeCount).isEqualTo(1)
        assertThat(report.totals.totalNodeCount).isEqualTo(2)
        assertThat(report.totals.activeNodeCount).isEqualTo(1)
    }

    @Test
    fun `sorts consumers by label and isolates results by tenant`() {
        val tenantA = createTenant("Tenant A")
        val tenantB = createTenant("Tenant B")
        seedApiKey(tenantA.id, "Zeta Service")
        seedApiKey(tenantA.id, "Alpha Service")
        // tenantB has its own consumer that must NOT bleed into tenantA's report.
        val isolated = seedApiKey(tenantB.id, "Other Tenant Service")
        seedNode(tenantB.id, isolated, "n1", listOf(0), OffsetDateTime.now())

        val report = withMediator { ListConsumerStatus(tenantA.id).query() }

        assertThat(report.consumers.map { it.label }).containsExactly("Alpha Service", "Zeta Service")
        assertThat(report.consumers.flatMap { it.nodes }).isEmpty()
        assertThat(report.totals.totalNodeCount).isZero
    }

    @Test
    fun `surfaces disabled api keys with their flag intact`() {
        val tenant = createTenant("Disabled Tenant")
        seedApiKey(tenant.id, "Revoked Plugin", enabled = false)

        val report = withMediator { ListConsumerStatus(tenant.id).query() }

        assertThat(report.consumers.single().enabled).isFalse
    }

    @Test
    fun `hides nodes whose last_seen_at is older than the show-stale window`() {
        // Default show-stale-window-hours = 1. A node older than that should not appear
        // in the report (it remains in the DB until the reaper sweeps it).
        val tenant = createTenant("Hide Window Tenant")
        val consumerId = seedApiKey(tenant.id, "Mixed Plugin")
        val now = OffsetDateTime.now()
        seedNode(tenant.id, consumerId, "fresh-stale", listOf(1), now.minusMinutes(15))
        seedNode(tenant.id, consumerId, "long-gone", listOf(2), now.minusHours(3))

        val report = withMediator { ListConsumerStatus(tenant.id).query() }

        val consumer = report.consumers.single()
        assertThat(consumer.nodes.map { it.nodeId }).containsExactly("fresh-stale")
        assertThat(report.totals.totalNodeCount).isEqualTo(1)
    }

    @Test
    fun `mostRecentNodeActivity reflects the freshest heartbeat across all consumers`() {
        val tenant = createTenant("Activity Tenant")
        val a = seedApiKey(tenant.id, "Plugin A")
        val b = seedApiKey(tenant.id, "Plugin B")
        val now = OffsetDateTime.now()
        seedNode(tenant.id, a, "n-a", listOf(0), now.minusSeconds(30))
        seedNode(tenant.id, b, "n-b", listOf(1), now.minusSeconds(2))

        val report = withMediator { ListConsumerStatus(tenant.id).query() }

        // Compare on second granularity to dodge timestamp round-trip drift.
        assertThat(report.totals.mostRecentNodeActivity).isNotNull
        val expectedFloor = now.minusSeconds(3).toEpochSecond()
        assertThat(report.totals.mostRecentNodeActivity!!.toEpochSecond())
            .isGreaterThanOrEqualTo(expectedFloor)
    }
}

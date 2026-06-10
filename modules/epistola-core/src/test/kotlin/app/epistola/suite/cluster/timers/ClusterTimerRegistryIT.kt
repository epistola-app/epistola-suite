package app.epistola.suite.cluster.timers

import app.epistola.suite.cluster.ClusterNodeRegistry
import app.epistola.suite.cluster.ClusterProperties
import app.epistola.suite.mediator.execute
import app.epistola.suite.observability.NodeIdentity
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@TestPropertySource(
    properties = [
        "epistola.cluster.timers.poll-interval-ms=60000",
        "epistola.cluster.timers.lease-duration-ms=30000",
        "epistola.cluster.timers.retry-delay-ms=30000",
    ],
)
class ClusterTimerRegistryIT : IntegrationTestBase() {

    @Autowired
    private lateinit var registry: ClusterTimerRegistry

    @Autowired
    private lateinit var nodeRegistry: ClusterNodeRegistry

    @Autowired
    private lateinit var nodeIdentity: NodeIdentity

    @Autowired
    private lateinit var properties: ClusterProperties

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var clock: Clock

    @Autowired
    private lateinit var jdbi: Jdbi

    @BeforeEach
    fun resetClock() {
        testClock.reset()
    }

    @Test
    fun `schedule upserts a durable timer`() {
        deleteTimer("timer-upsert")

        val first = registry.schedule(
            timerKey = "timer-upsert",
            routingKey = "tenant-a",
            timerType = "test",
            dueAt = now().plusMinutes(1),
            payload = mapOf("attempt" to 1),
        )
        val second = registry.schedule(
            timerKey = "timer-upsert",
            routingKey = "tenant-b",
            timerType = "test",
            dueAt = now().plusMinutes(2),
            payload = mapOf("attempt" to 2),
        )

        assertThat(first.timerKey).isEqualTo("timer-upsert")
        assertThat(first.tenantKey).isNull()
        assertThat(first.requiredCapability).isEqualTo(ClusterProperties.DEFAULT_CAPABILITY)
        assertThat(second.routingKey).isEqualTo("tenant-b")
        assertThat(second.payload["attempt"]).isEqualTo(2)
        assertThat(second.status).isEqualTo(ClusterTimerStatus.SCHEDULED)
    }

    @Test
    fun `schedule can create a tenant-scoped timer`() {
        val tenant = createTenant("Timer Tenant")
        deleteTimer("timer-tenant")

        val timer = registry.schedule(
            timerKey = "timer-tenant",
            routingKey = tenant.id.value,
            timerType = "test",
            dueAt = now().plusMinutes(1),
            tenantKey = tenant.id,
        )

        assertThat(timer.tenantKey).isEqualTo(tenant.id)
        assertThat(registry.find("timer-tenant")?.tenantKey).isEqualTo(tenant.id)
    }

    @Test
    fun `schedule can create a system timer`() {
        deleteTimer("timer-system")

        val timer = registry.schedule(
            timerKey = "timer-system",
            routingKey = "system:maintenance",
            timerType = "test",
            dueAt = now().plusMinutes(1),
        )

        assertThat(timer.tenantKey).isNull()
        assertThat(registry.find("timer-system")?.tenantKey).isNull()
    }

    @Test
    fun `schedule command creates a durable timer`() {
        withMediator {
            deleteTimer("timer-command")

            val timer = ScheduleClusterTimer(
                timerKey = "timer-command",
                routingKey = "tenant-command",
                timerType = "test",
                dueAt = now().plusMinutes(1),
                payload = mapOf("source" to "command"),
            ).execute()

            assertThat(timer.timerKey).isEqualTo("timer-command")
            assertThat(timer.payload["source"]).isEqualTo("command")
            assertThat(registry.find("timer-command")?.routingKey).isEqualTo("tenant-command")
        }
    }

    @Test
    fun `cancel deletes a durable timer`() {
        deleteTimer("timer-cancel")
        registry.schedule("timer-cancel", "tenant-a", "test", now().plusMinutes(1))

        val cancelled = registry.cancel("timer-cancel")

        assertThat(cancelled).isTrue()
        assertThat(registry.find("timer-cancel")).isNull()
    }

    @Test
    fun `cancel respects tenant scope`() {
        val ownerTenant = createTenant("Timer Owner")
        val otherTenant = createTenant("Timer Other")
        deleteTimer("timer-cancel-tenant")
        registry.schedule(
            timerKey = "timer-cancel-tenant",
            routingKey = ownerTenant.id.value,
            timerType = "test",
            dueAt = now().plusMinutes(1),
            tenantKey = ownerTenant.id,
        )

        val wrongTenantCancelled = registry.cancel("timer-cancel-tenant", otherTenant.id)
        val ownerCancelled = registry.cancel("timer-cancel-tenant", ownerTenant.id)

        assertThat(wrongTenantCancelled).isFalse()
        assertThat(ownerCancelled).isTrue()
        assertThat(registry.find("timer-cancel-tenant")).isNull()
    }

    @Test
    fun `cancel command deletes a durable timer`() {
        withMediator {
            deleteTimer("timer-cancel-command")
            registry.schedule("timer-cancel-command", "tenant-a", "test", now().plusMinutes(1))

            val cancelled = CancelClusterTimer("timer-cancel-command").execute()

            assertThat(cancelled).isTrue()
            assertThat(registry.find("timer-cancel-command")).isNull()
        }
    }

    @Test
    fun `tenant scoped timers cascade when tenant is deleted`() {
        val tenant = createTenant("Timer Cascade")
        deleteTimer("timer-cascade")
        registry.schedule(
            timerKey = "timer-cascade",
            routingKey = tenant.id.value,
            timerType = "test",
            dueAt = now().plusMinutes(1),
            tenantKey = tenant.id,
        )

        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate("DELETE FROM tenants WHERE id = :tenantKey")
                .bind("tenantKey", tenant.id)
                .execute()
        }

        assertThat(registry.find("timer-cascade")).isNull()
    }

    @Test
    fun `claim due leases a timer for the current node`() {
        deleteTimer("timer-claim")
        registry.schedule("timer-claim", "tenant-a", "test", now().plusMinutes(1))
        testClock.advanceBy(Duration.ofSeconds(61))
        nodeRegistry.heartbeat()

        val claimed = registry.claimDue(listOf("timer-claim"))

        assertThat(claimed).hasSize(1)
        assertThat(claimed.single().status).isEqualTo(ClusterTimerStatus.RUNNING)
        assertThat(claimed.single().leaseOwnerNodeId).isEqualTo(nodeIdentity.nodeId)
        assertThat(claimed.single().attemptCount).isEqualTo(1)
    }

    @Test
    fun `competing nodes can only claim a timer once`() = scenario {
        given {
            val timerKey = uniqueKey("timer-concurrent-claim")
            execute(
                ScheduleClusterTimer(
                    timerKey = timerKey,
                    routingKey = "tenant-a",
                    timerType = "test",
                    dueAt = now().plusMinutes(1),
                ),
            )
            testClock.advanceBy(Duration.ofSeconds(61))
            nodeRegistry.heartbeat()
            nodeRegistryFor("timer-node-b").heartbeat()
            TimerClaimSetup(
                timerKey = timerKey,
                registries = listOf(registry, registryFor("timer-node-b")),
            )
        }.whenever { setup ->
            val executor = Executors.newFixedThreadPool(2)
            val barrier = CyclicBarrier(2)
            try {
                setup.registries
                    .map { candidateRegistry ->
                        executor.submit<List<ClusterTimer>> {
                            barrier.await()
                            withClock {
                                candidateRegistry.claimDue(listOf(setup.timerKey))
                            }
                        }
                    }
                    .flatMap { it.get(10, TimeUnit.SECONDS) }
            } finally {
                executor.shutdownNow()
            }
        }.then { setup, claimed ->
            assertThat(claimed).hasSize(1)
            assertThat(claimed.single().timerKey).isEqualTo(setup.timerKey)
            assertThat(registry.find(setup.timerKey)?.leaseOwnerNodeId)
                .isIn(nodeIdentity.nodeId, "timer-node-b")
        }
    }

    @Test
    fun `claim due ignores timers requiring a capability the current node does not advertise`() {
        deleteTimer("timer-pdf-render")
        registry.schedule(
            timerKey = "timer-pdf-render",
            routingKey = "tenant-a",
            timerType = "test",
            dueAt = now().plusMinutes(1),
            requiredCapability = "pdf-render",
        )
        testClock.advanceBy(Duration.ofSeconds(61))
        nodeRegistry.heartbeat()

        val claimed = registry.claimDue(listOf("timer-pdf-render"))

        assertThat(claimed).isEmpty()
        assertThat(registry.find("timer-pdf-render")?.leaseOwnerNodeId).isNull()
    }

    @Test
    fun `complete deletes a running timer owned by the current node`() {
        deleteTimer("timer-complete")
        registry.schedule("timer-complete", "tenant-a", "test", now().plusMinutes(1))
        testClock.advanceBy(Duration.ofSeconds(61))
        nodeRegistry.heartbeat()
        registry.claimDue(listOf("timer-complete"))

        val completed = registry.complete("timer-complete")

        assertThat(completed).isTrue()
        assertThat(registry.find("timer-complete")).isNull()
    }

    @Test
    fun `expired running timer can be reclaimed`() {
        nodeRegistry.heartbeat()
        deleteTimer("timer-expired")
        insertExpiredRunningTimer("timer-expired")

        val claimed = registry.claimDue(listOf("timer-expired"))

        assertThat(claimed).hasSize(1)
        assertThat(claimed.single().leaseOwnerNodeId).isEqualTo(nodeIdentity.nodeId)
        assertThat(claimed.single().attemptCount).isEqualTo(2)
    }

    @Test
    fun `reschedule keeps timer with next due time`() {
        deleteTimer("timer-reschedule")
        registry.schedule("timer-reschedule", "tenant-a", "test", now().plusMinutes(1))
        testClock.advanceBy(Duration.ofSeconds(61))
        nodeRegistry.heartbeat()
        registry.claimDue(listOf("timer-reschedule"))
        val nextDueAt = now().plusMinutes(5)

        val rescheduled = registry.reschedule("timer-reschedule", nextDueAt, mapOf("phase" to "next"))

        val timer = registry.find("timer-reschedule")
        assertThat(rescheduled).isTrue()
        assertThat(timer?.status).isEqualTo(ClusterTimerStatus.SCHEDULED)
        assertThat(timer?.payload?.get("phase")).isEqualTo("next")
        assertThat(timer?.leaseOwnerNodeId).isNull()
    }

    private fun deleteTimer(timerKey: String) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate("DELETE FROM cluster_timers WHERE timer_key = :timerKey")
                .bind("timerKey", timerKey)
                .execute()
        }
    }

    private fun insertExpiredRunningTimer(timerKey: String) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO cluster_timers (
                    timer_key, routing_key, timer_type, due_at, payload, status,
                    lease_owner_node_id, lease_expires_at, attempt_count
                )
                VALUES (
                    :timerKey, 'tenant-a', 'test', :dueAt, '{}'::jsonb, 'running',
                    'dead-node', :leaseExpiresAt, 1
                )
                """,
            )
                .bind("timerKey", timerKey)
                .bind("dueAt", now().minusSeconds(1))
                .bind("leaseExpiresAt", now().minusSeconds(1))
                .execute()
        }
    }

    private fun nodeRegistryFor(nodeId: String): ClusterNodeRegistry = ClusterNodeRegistry(
        jdbi = jdbi,
        objectMapper = objectMapper,
        nodeIdentity = NodeIdentity(nodeId),
        properties = properties,
        clock = clock,
    )

    private fun registryFor(nodeId: String): ClusterTimerRegistry = ClusterTimerRegistry(
        jdbi = jdbi,
        objectMapper = objectMapper,
        nodeIdentity = NodeIdentity(nodeId),
        properties = properties,
        clock = clock,
    )

    private fun uniqueKey(prefix: String): String = "$prefix-${UUID.randomUUID()}"

    private fun now(): OffsetDateTime = OffsetDateTime.now(testClock)

    private data class TimerClaimSetup(
        val timerKey: String,
        val registries: List<ClusterTimerRegistry>,
    )
}

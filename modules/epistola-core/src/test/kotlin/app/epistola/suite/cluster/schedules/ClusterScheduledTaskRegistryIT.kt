package app.epistola.suite.cluster.schedules

import app.epistola.suite.cluster.ClusterNodeRegistry
import app.epistola.suite.cluster.ClusterProperties
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
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
        "epistola.cluster.scheduled-tasks.lease-duration-ms=30000",
        "epistola.cluster.scheduled-tasks.retry-delay-ms=30000",
    ],
)
class ClusterScheduledTaskRegistryIT : IntegrationTestBase() {

    @Autowired
    private lateinit var registry: ClusterScheduledTaskRegistry

    @Autowired
    private lateinit var nodeRegistry: ClusterNodeRegistry

    @Autowired
    private lateinit var nodeIdentity: NodeIdentity

    @Autowired
    private lateinit var properties: ClusterProperties

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var scheduleCalculator: ClusterScheduledTaskScheduleCalculator

    @Autowired
    private lateinit var clock: Clock

    @Autowired
    private lateinit var jdbi: Jdbi

    @BeforeEach
    fun resetClock() {
        testClock.reset()
    }

    @Test
    fun `upsert creates a durable scheduled task`() {
        deleteTask("task-upsert")

        val task = registry.upsert(
            ClusterScheduledTaskDefinition(
                taskKey = "task-upsert",
                routingKey = "system:task-upsert",
                taskType = "test",
                schedule = ClusterScheduledTaskSchedule.FixedDelay(60_000),
                payload = mapOf("source" to "test"),
            ),
        )

        assertThat(task.taskKey).isEqualTo("task-upsert")
        assertThat(task.tenantKey).isNull()
        assertThat(task.requiredCapability).isEqualTo(ClusterProperties.DEFAULT_CAPABILITY)
        assertThat(task.payload["source"]).isEqualTo("test")
        assertThat(task.scheduleKind).isEqualTo(ClusterScheduledTaskScheduleKind.FIXED_DELAY)
        assertThat(task.nextDueAt).isAfter(now())
    }

    @Test
    fun `upsert preserves next due when schedule shape is unchanged`() {
        deleteTask("task-preserve")
        registry.upsert(
            ClusterScheduledTaskDefinition(
                taskKey = "task-preserve",
                routingKey = "system:task-preserve",
                taskType = "test",
                schedule = ClusterScheduledTaskSchedule.FixedRate(60_000),
            ),
        )
        forceDue("task-preserve")

        val updated = registry.upsert(
            ClusterScheduledTaskDefinition(
                taskKey = "task-preserve",
                routingKey = "system:task-preserve",
                taskType = "test",
                schedule = ClusterScheduledTaskSchedule.FixedRate(60_000),
                payload = mapOf("version" to 2),
            ),
        )

        assertThat(updated.nextDueAt).isBefore(now())
        assertThat(updated.payload["version"]).isEqualTo(2)
    }

    @Test
    fun `upsert resets next due when schedule shape changes`() {
        deleteTask("task-reset")
        registry.upsert(
            ClusterScheduledTaskDefinition(
                taskKey = "task-reset",
                routingKey = "system:task-reset",
                taskType = "test",
                schedule = ClusterScheduledTaskSchedule.FixedRate(60_000),
            ),
        )
        forceDue("task-reset")

        val updated = registry.upsert(
            ClusterScheduledTaskDefinition(
                taskKey = "task-reset",
                routingKey = "system:task-reset",
                taskType = "test",
                schedule = ClusterScheduledTaskSchedule.FixedRate(120_000),
            ),
        )

        assertThat(updated.nextDueAt).isAfter(now())
    }

    @Test
    fun `tenant scoped tasks cascade when tenant is deleted`() {
        val tenant = createTenant("Scheduled Task Tenant")
        deleteTask("task-tenant")
        registry.upsert(
            ClusterScheduledTaskDefinition(
                taskKey = "task-tenant",
                tenantKey = tenant.id,
                routingKey = tenant.id.value,
                taskType = "test",
                schedule = ClusterScheduledTaskSchedule.FixedDelay(60_000),
            ),
        )

        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate("DELETE FROM tenants WHERE id = :tenantKey")
                .bind("tenantKey", tenant.id)
                .execute()
        }

        assertThat(registry.find("task-tenant")).isNull()
    }

    @Test
    fun `claim due leases a scheduled task for the current node`() {
        seedDueTask("task-claim")
        nodeRegistry.heartbeat()

        val claimed = registry.claimDue(listOf("task-claim"))

        assertThat(claimed).hasSize(1)
        assertThat(claimed.single().leaseOwnerNodeId).isEqualTo(nodeIdentity.nodeId)
        assertThat(claimed.single().attemptCount).isEqualTo(1)
    }

    @Test
    fun `each capable node tasks maintain independent node state`() = scenario {
        given {
            val taskKey = uniqueKey("task-each-node")
            execute(
                UpsertClusterScheduledTask(
                    ClusterScheduledTaskDefinition(
                        taskKey = taskKey,
                        routingKey = "system:$taskKey",
                        taskType = "test",
                        schedule = ClusterScheduledTaskSchedule.FixedRate(60_000),
                        executionScope = ClusterScheduledTaskExecutionScope.EACH_CAPABLE_NODE,
                    ),
                ),
            )
            testClock.advanceBy(Duration.ofSeconds(61))
            nodeRegistry.heartbeat()
            nodeRegistryFor("scheduled-node-b").heartbeat()
            ScheduledTaskClaimSetup(
                taskKey = taskKey,
                registries = listOf(registry, registryFor("scheduled-node-b")),
            )
        }.whenever { setup ->
            setup.registries.flatMap { candidateRegistry ->
                withClock { candidateRegistry.claimDue(listOf(setup.taskKey)) }
            }
        }.then { setup, claimed ->
            assertThat(claimed).hasSize(2)
            assertThat(claimed.map { it.taskKey }).containsOnly(setup.taskKey)
            assertThat(registry.listNodeStates().filter { it.taskKey == setup.taskKey }.map { it.nodeId })
                .containsExactlyInAnyOrder(nodeIdentity.nodeId, "scheduled-node-b")
        }
    }

    @Test
    fun `competing nodes can only claim a scheduled task once`() = scenario {
        given {
            val taskKey = uniqueKey("task-concurrent-claim")
            execute(
                UpsertClusterScheduledTask(
                    ClusterScheduledTaskDefinition(
                        taskKey = taskKey,
                        routingKey = "system:$taskKey",
                        taskType = "test",
                        schedule = ClusterScheduledTaskSchedule.FixedRate(60_000),
                    ),
                ),
            )
            testClock.advanceBy(Duration.ofSeconds(61))
            nodeRegistry.heartbeat()
            nodeRegistryFor("scheduled-node-b").heartbeat()
            ScheduledTaskClaimSetup(
                taskKey = taskKey,
                registries = listOf(registry, registryFor("scheduled-node-b")),
            )
        }.whenever { setup ->
            val executor = Executors.newFixedThreadPool(2)
            val barrier = CyclicBarrier(2)
            try {
                setup.registries
                    .map { candidateRegistry ->
                        executor.submit<List<ClusterScheduledTask>> {
                            barrier.await()
                            withClock {
                                candidateRegistry.claimDue(listOf(setup.taskKey))
                            }
                        }
                    }
                    .flatMap { it.get(10, TimeUnit.SECONDS) }
            } finally {
                executor.shutdownNow()
            }
        }.then { setup, claimed ->
            assertThat(claimed).hasSize(1)
            assertThat(claimed.single().taskKey).isEqualTo(setup.taskKey)
            assertThat(registry.find(setup.taskKey)?.leaseOwnerNodeId)
                .isIn(nodeIdentity.nodeId, "scheduled-node-b")
        }
    }

    @Test
    fun `claim due ignores scheduled tasks requiring a capability the current node does not advertise`() {
        deleteTask("task-pdf-render")
        registry.upsert(
            ClusterScheduledTaskDefinition(
                taskKey = "task-pdf-render",
                routingKey = "system:task-pdf-render",
                taskType = "test",
                requiredCapability = "pdf-render",
                schedule = ClusterScheduledTaskSchedule.FixedRate(60_000),
            ),
        )
        testClock.advanceBy(Duration.ofSeconds(61))
        nodeRegistry.heartbeat()

        val claimed = registry.claimDue(listOf("task-pdf-render"))

        assertThat(claimed).isEmpty()
        assertThat(registry.find("task-pdf-render")?.leaseOwnerNodeId).isNull()
    }

    @Test
    fun `complete advances the next due occurrence`() {
        seedDueTask("task-complete")
        nodeRegistry.heartbeat()
        val task = registry.claimDue(listOf("task-complete")).single()
        val nextDueAt = now().plusMinutes(5)

        val completed = registry.complete(task.taskKey, nextDueAt)

        val stored = registry.find("task-complete")
        assertThat(completed).isTrue()
        assertThat(stored?.nextDueAt).isEqualTo(nextDueAt)
        assertThat(stored?.leaseOwnerNodeId).isNull()
        assertThat(stored?.consecutiveFailures).isZero()
    }

    @Test
    fun `failure releases lease and increments consecutive failures`() {
        seedDueTask("task-fail")
        nodeRegistry.heartbeat()
        val task = registry.claimDue(listOf("task-fail")).single()
        val retryAt = now().plusMinutes(1)

        val failed = registry.fail(task.taskKey, retryAt, "boom")

        val stored = registry.find("task-fail")
        assertThat(failed).isTrue()
        assertThat(stored?.nextDueAt).isEqualTo(retryAt)
        assertThat(stored?.leaseOwnerNodeId).isNull()
        assertThat(stored?.consecutiveFailures).isEqualTo(1)
        assertThat(stored?.lastError).isEqualTo("boom")
    }

    @Test
    fun `complete by a stale owner is a no-op after another node reclaims the task`() {
        seedDueTask("task-reclaim-complete")
        nodeRegistry.heartbeat()
        val claimedByA = registry.claimDue(listOf("task-reclaim-complete")).single()
        assertThat(claimedByA.leaseOwnerNodeId).isEqualTo(nodeIdentity.nodeId)

        // A's lease lapses; B reclaims the same occurrence.
        testClock.advanceBy(Duration.ofSeconds(31))
        nodeRegistryFor("scheduled-node-b").heartbeat()
        val claimedByB = registryFor("scheduled-node-b").claimDue(listOf("task-reclaim-complete")).single()
        assertThat(claimedByB.leaseOwnerNodeId).isEqualTo("scheduled-node-b")

        // A — believing it still owns the work — tries to complete it.
        val completedByA = registry.complete("task-reclaim-complete", now().plusMinutes(5))

        val stored = registry.find("task-reclaim-complete")
        assertThat(completedByA).isFalse()
        assertThat(stored?.leaseOwnerNodeId).isEqualTo("scheduled-node-b")
        assertThat(stored?.lastCompletedAt).isNull()
        assertThat(stored?.nextDueAt).isNotEqualTo(now().plusMinutes(5))
    }

    @Test
    fun `fail by a stale owner is a no-op after another node reclaims the task`() {
        seedDueTask("task-reclaim-fail")
        nodeRegistry.heartbeat()
        registry.claimDue(listOf("task-reclaim-fail")).single()

        testClock.advanceBy(Duration.ofSeconds(31))
        nodeRegistryFor("scheduled-node-b").heartbeat()
        registryFor("scheduled-node-b").claimDue(listOf("task-reclaim-fail")).single()

        val failedByA = registry.fail("task-reclaim-fail", now().plusMinutes(1), "stale")

        val stored = registry.find("task-reclaim-fail")
        assertThat(failedByA).isFalse()
        assertThat(stored?.leaseOwnerNodeId).isEqualTo("scheduled-node-b")
        assertThat(stored?.consecutiveFailures).isZero()
        assertThat(stored?.lastError).isNull()
    }

    @Test
    fun `renewing a lease keeps the task owned so another node cannot reclaim it`() {
        seedDueTask("task-renew")
        nodeRegistry.heartbeat()
        registry.claimDue(listOf("task-renew")).single() // lease expires in 30s

        // Just before the original lease lapses, A renews it.
        testClock.advanceBy(Duration.ofSeconds(25))
        assertThat(registry.renewLeases(listOf("task-renew"))).isEqualTo(1)

        // Past the original 30s lease, but the renewal pushed it out another 30s.
        testClock.advanceBy(Duration.ofSeconds(10))
        nodeRegistryFor("scheduled-node-b").heartbeat()
        val nodeB = registryFor("scheduled-node-b")

        assertThat(nodeB.renewLeases(listOf("task-renew"))).isZero() // B owns nothing to renew
        assertThat(nodeB.claimDue(listOf("task-renew"))).isEmpty() // lease still held by A
        assertThat(registry.find("task-renew")?.leaseOwnerNodeId).isEqualTo(nodeIdentity.nodeId)
    }

    @Test
    fun `commands can disable enable trigger and list tasks`() {
        withMediator {
            deleteTask("task-command")
            UpsertClusterScheduledTask(
                ClusterScheduledTaskDefinition(
                    taskKey = "task-command",
                    routingKey = "system:task-command",
                    taskType = "test",
                    schedule = ClusterScheduledTaskSchedule.FixedDelay(60_000),
                ),
            ).execute()

            assertThat(DisableClusterScheduledTask("task-command").execute()).isTrue()
            assertThat(registry.find("task-command")?.enabled).isFalse()
            assertThat(EnableClusterScheduledTask("task-command").execute()).isTrue()
            assertThat(TriggerClusterScheduledTaskNow("task-command").execute()).isTrue()

            val tasks = ListClusterScheduledTasks.query()
            assertThat(tasks.map { it.taskKey }).contains("task-command")
            assertThat(registry.find("task-command")?.nextDueAt).isEqualTo(now())
        }
    }

    @Test
    fun `startup registration upserts configured definitions and leaves unregistered tasks unchanged`() = scenario {
        given {
            val registeredKey = uniqueKey("task-startup-registered")
            val unregisteredKey = uniqueKey("task-startup-unregistered")
            execute(
                UpsertClusterScheduledTask(
                    ClusterScheduledTaskDefinition(
                        taskKey = unregisteredKey,
                        routingKey = "system:$unregisteredKey",
                        taskType = "test",
                        schedule = ClusterScheduledTaskSchedule.FixedDelay(60_000),
                        enabled = false,
                    ),
                ),
            )
            StartupRegistrationSetup(
                registeredKey = registeredKey,
                unregisteredKey = unregisteredKey,
                registrar = ClusterScheduledTaskRegistrar(
                    registry = registry,
                    definitions = listOf(
                        ClusterScheduledTaskDefinition(
                            taskKey = registeredKey,
                            routingKey = "system:$registeredKey",
                            taskType = "test",
                            schedule = ClusterScheduledTaskSchedule.FixedRate(120_000),
                            payload = mapOf("registered" to true),
                        ),
                    ),
                ),
            )
        }.whenever { setup ->
            setup.registrar.registerDefinitions()
        }.then { setup, _ ->
            val registered = registry.find(setup.registeredKey)
            val unregistered = registry.find(setup.unregisteredKey)
            assertThat(registered?.scheduleKind).isEqualTo(ClusterScheduledTaskScheduleKind.FIXED_RATE)
            assertThat(registered?.payload?.get("registered")).isEqualTo(true)
            assertThat(unregistered?.enabled).isFalse()
        }
    }

    private fun seedDueTask(taskKey: String) {
        deleteTask(taskKey)
        registry.upsert(
            ClusterScheduledTaskDefinition(
                taskKey = taskKey,
                routingKey = "system:$taskKey",
                taskType = "test",
                schedule = ClusterScheduledTaskSchedule.FixedRate(60_000),
            ),
        )
        testClock.advanceBy(Duration.ofSeconds(61))
    }

    private fun forceDue(taskKey: String) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate("UPDATE cluster_tasks_scheduled SET next_due_at = :dueAt WHERE task_key = :taskKey")
                .bind("taskKey", taskKey)
                .bind("dueAt", now().minusSeconds(1))
                .execute()
        }
    }

    private fun now(): OffsetDateTime = OffsetDateTime.now(testClock)

    private fun deleteTask(taskKey: String) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate("DELETE FROM cluster_tasks_scheduled WHERE task_key = :taskKey")
                .bind("taskKey", taskKey)
                .execute()
        }
    }

    private fun nodeRegistryFor(nodeId: String): ClusterNodeRegistry = ClusterNodeRegistry(
        jdbi = jdbi,
        objectMapper = objectMapper,
        nodeIdentity = NodeIdentity(nodeId),
        properties = properties,
    )

    private fun registryFor(nodeId: String): ClusterScheduledTaskRegistry = ClusterScheduledTaskRegistry(
        jdbi = jdbi,
        objectMapper = objectMapper,
        nodeIdentity = NodeIdentity(nodeId),
        properties = properties,
        scheduleCalculator = scheduleCalculator,
    )

    private fun uniqueKey(prefix: String): String = "$prefix-${UUID.randomUUID()}"

    private data class ScheduledTaskClaimSetup(
        val taskKey: String,
        val registries: List<ClusterScheduledTaskRegistry>,
    )

    private data class StartupRegistrationSetup(
        val registeredKey: String,
        val unregisteredKey: String,
        val registrar: ClusterScheduledTaskRegistrar,
    )
}

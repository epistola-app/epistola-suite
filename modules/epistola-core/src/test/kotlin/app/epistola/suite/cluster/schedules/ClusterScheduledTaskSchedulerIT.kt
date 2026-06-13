package app.epistola.suite.cluster.schedules

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import java.time.Duration
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

@TestPropertySource(
    properties = [
        "epistola.cluster.scheduled-tasks.lease-duration-ms=30000",
        "epistola.cluster.scheduled-tasks.retry-delay-ms=30000",
    ],
)
@Import(ClusterScheduledTaskSchedulerIT.TaskHandlerConfiguration::class)
class ClusterScheduledTaskSchedulerIT : IntegrationTestBase() {

    @Autowired
    private lateinit var scheduler: ClusterScheduledTaskScheduler

    @Autowired
    private lateinit var registry: ClusterScheduledTaskRegistry

    @Autowired
    private lateinit var handler: RecordingClusterScheduledTaskHandler

    @Autowired
    private lateinit var jdbi: Jdbi

    @BeforeEach
    fun reset() {
        testClock.reset()
        handler.handled.clear()
        handler.failTaskKeys.clear()
        handler.tenantSeen.clear()
        withMediator {
            DisableClusterScheduledTask("scheduler-missing-handler").execute()
            DisableClusterScheduledTask("scheduler-missing-handler-held").execute()
            DisableClusterScheduledTask("scheduler-missing-handler-eachnode-orphan").execute()
            DisableClusterScheduledTask("scheduler-missing-handler-eachnode-held").execute()
            DisableClusterScheduledTask("scheduler-success").execute()
            DisableClusterScheduledTask("scheduler-failure").execute()
            DisableClusterScheduledTask("scheduler-each-node").execute()
            DisableClusterScheduledTask("scheduler-virtual-time").execute()
            DisableClusterScheduledTask("scheduler-tenant").execute()
        }
    }

    @Test
    fun `poll dispatches an owned scheduled task and advances recurrence`() {
        seedDueTask("scheduler-success")

        scheduler.poll()

        val task = registry.find("scheduler-success")
        assertThat(handler.handled).containsExactly("scheduler-success")
        assertThat(task?.leaseOwnerNodeId).isNull()
        assertThat(task?.nextDueAt).isAfter(now())
        assertThat(task?.consecutiveFailures).isZero()
        // A system-wide task (no tenantKey) runs with no principal → system/null log attribution.
        assertThat(handler.tenantSeen).doesNotContainKey("scheduler-success")
    }

    @Test
    fun `poll dispatches an each capable node scheduled task`() {
        withMediator {
            UpsertClusterScheduledTask(
                ClusterScheduledTaskDefinition(
                    taskKey = "scheduler-each-node",
                    routingKey = "system:scheduler-each-node",
                    taskType = RecordingClusterScheduledTaskHandler.TYPE,
                    schedule = ClusterScheduledTaskSchedule.FixedRate(60_000),
                    executionScope = ClusterScheduledTaskExecutionScope.EACH_CAPABLE_NODE,
                ),
            ).execute()
        }
        testClock.advanceBy(Duration.ofSeconds(61))

        scheduler.poll()

        val nodeState = registry.listNodeStates().single { it.taskKey == "scheduler-each-node" }
        assertThat(handler.handled).containsExactly("scheduler-each-node")
        assertThat(nodeState.nextDueAt).isAfter(now())
        assertThat(nodeState.consecutiveFailures).isZero()
    }

    @Test
    fun `advanceTimeBy runs a task that became due in the advanced window`() {
        withMediator {
            UpsertClusterScheduledTask(
                ClusterScheduledTaskDefinition(
                    taskKey = "scheduler-virtual-time",
                    routingKey = "system:scheduler-virtual-time",
                    taskType = RecordingClusterScheduledTaskHandler.TYPE,
                    schedule = ClusterScheduledTaskSchedule.FixedRate(60_000),
                ),
            ).execute()
        }

        // Not yet due: moving time without firing keeps it pending.
        testClock.advanceBy(Duration.ofSeconds(30))
        assertThat(handler.handled).isEmpty()

        // Virtual time: advance past due and run everything that became due.
        scheduling.advanceTimeBy(Duration.ofSeconds(31))

        val task = registry.find("scheduler-virtual-time")
        assertThat(handler.handled).containsExactly("scheduler-virtual-time")
        assertThat(task?.leaseOwnerNodeId).isNull()
        assertThat(task?.nextDueAt).isAfter(now())
    }

    @Test
    fun `tenant-scoped task runs under its tenant's system principal`() {
        val tenant: TenantKey = createTenant("Cluster Task Tenant").id
        withMediator {
            UpsertClusterScheduledTask(
                ClusterScheduledTaskDefinition(
                    taskKey = "scheduler-tenant",
                    routingKey = "system:scheduler-tenant",
                    taskType = RecordingClusterScheduledTaskHandler.TYPE,
                    schedule = ClusterScheduledTaskSchedule.FixedRate(60_000),
                    tenantKey = tenant,
                ),
            ).execute()
        }
        testClock.advanceBy(Duration.ofSeconds(61))

        scheduler.poll()

        assertThat(handler.handled).contains("scheduler-tenant")
        // The handler ran under the tenant's system principal (mediator authz + log attribution).
        assertThat(handler.tenantSeen["scheduler-tenant"]).isEqualTo(tenant.value)
    }

    @Test
    fun `poll records failure and retries the same occurrence`() {
        seedDueTask("scheduler-failure")
        handler.failTaskKeys += "scheduler-failure"

        scheduler.poll()

        val task = registry.find("scheduler-failure")
        assertThat(handler.handled).containsExactly("scheduler-failure")
        assertThat(task?.leaseOwnerNodeId).isNull()
        assertThat(task?.consecutiveFailures).isEqualTo(1)
        assertThat(task?.lastError).isEqualTo("planned failure")
        assertThat(task?.nextDueAt).isEqualTo(now().plusSeconds(30))
    }

    @Test
    fun `poll deletes a no-handler task inline when no live node carries it`() {
        withMediator {
            UpsertClusterScheduledTask(
                ClusterScheduledTaskDefinition(
                    taskKey = "scheduler-missing-handler",
                    routingKey = "system:scheduler-missing-handler",
                    taskType = "missing-handler",
                    schedule = ClusterScheduledTaskSchedule.FixedRate(60_000),
                ),
            ).execute()
        }
        testClock.advanceBy(Duration.ofSeconds(61))

        // No node carries this task type, so when it fires with no handler it is
        // genuinely orphaned and is deleted inline — no waiting for the reconciler.
        scheduler.poll()

        assertThat(handler.handled).isEmpty()
        assertThat(registry.find("scheduler-missing-handler")).isNull()
    }

    @Test
    fun `poll advances a no-handler task without deleting it while a node still carries it`() {
        withMediator {
            UpsertClusterScheduledTask(
                ClusterScheduledTaskDefinition(
                    taskKey = "scheduler-missing-handler-held",
                    routingKey = "system:scheduler-missing-handler-held",
                    taskType = "missing-handler",
                    schedule = ClusterScheduledTaskSchedule.FixedRate(60_000),
                ),
            ).execute()
        }
        // A different, currently-live node carries the definition (e.g. a rolling
        // deploy in-between state, or a non-handler node grabbed it by capability).
        seedNode("holder-node", now())
        insertRegistration("scheduler-missing-handler-held", "holder-node")
        testClock.advanceBy(Duration.ofSeconds(61))

        scheduler.poll()

        // Not orphaned: quietly advanced for a carrying node to run, not deleted, no
        // failure/error state.
        val task = registry.find("scheduler-missing-handler-held")
        assertThat(handler.handled).isEmpty()
        assertThat(task).isNotNull()
        assertThat(task?.enabled).isTrue()
        assertThat(task?.leaseOwnerNodeId).isNull()
        assertThat(task?.consecutiveFailures).isZero()
        assertThat(task?.lastError).isNull()
        assertThat(task?.nextDueAt).isAfter(now())
    }

    @Test
    fun `poll deletes an orphaned each-capable-node no-handler task inline`() {
        val key = "scheduler-missing-handler-eachnode-orphan"
        withMediator {
            UpsertClusterScheduledTask(
                ClusterScheduledTaskDefinition(
                    taskKey = key,
                    routingKey = "system:$key",
                    taskType = "missing-handler",
                    schedule = ClusterScheduledTaskSchedule.FixedRate(60_000),
                    executionScope = ClusterScheduledTaskExecutionScope.EACH_CAPABLE_NODE,
                ),
            ).execute()
        }
        testClock.advanceBy(Duration.ofSeconds(61))

        scheduler.poll()

        assertThat(handler.handled).isEmpty()
        assertThat(registry.find(key)).isNull()
        // Per-node state created during the claim cascades away with the deleted task.
        assertThat(registry.listNodeStates().filter { it.taskKey == key }).isEmpty()
    }

    @Test
    fun `poll advances an each-capable-node no-handler task without deleting while a node carries it`() {
        val key = "scheduler-missing-handler-eachnode-held"
        withMediator {
            UpsertClusterScheduledTask(
                ClusterScheduledTaskDefinition(
                    taskKey = key,
                    routingKey = "system:$key",
                    taskType = "missing-handler",
                    schedule = ClusterScheduledTaskSchedule.FixedRate(60_000),
                    executionScope = ClusterScheduledTaskExecutionScope.EACH_CAPABLE_NODE,
                ),
            ).execute()
        }
        seedNode("holder-node", now())
        insertRegistration(key, "holder-node")
        testClock.advanceBy(Duration.ofSeconds(61))

        scheduler.poll()

        assertThat(handler.handled).isEmpty()
        assertThat(registry.find(key)).isNotNull()
        // The current node's per-node occurrence was advanced (skipped), not deleted.
        val nodeStates = registry.listNodeStates().filter { it.taskKey == key }
        assertThat(nodeStates).hasSize(1)
        assertThat(nodeStates.single().nextDueAt).isAfter(now())
    }

    private fun seedDueTask(taskKey: String) {
        withMediator {
            UpsertClusterScheduledTask(
                ClusterScheduledTaskDefinition(
                    taskKey = taskKey,
                    routingKey = "system:$taskKey",
                    taskType = RecordingClusterScheduledTaskHandler.TYPE,
                    schedule = ClusterScheduledTaskSchedule.FixedRate(60_000),
                ),
            ).execute()
        }
        testClock.advanceBy(Duration.ofSeconds(61))
    }

    private fun now(): OffsetDateTime = OffsetDateTime.now(testClock)

    private fun seedNode(nodeId: String, lastSeenAt: OffsetDateTime) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO cluster_nodes (node_id, capabilities, version, joined_at, last_seen_at, metadata)
                VALUES (:nodeId, '["suite"]'::jsonb, '1.0.0', :lastSeenAt, :lastSeenAt, '{}'::jsonb)
                ON CONFLICT (node_id) DO UPDATE SET last_seen_at = EXCLUDED.last_seen_at
                """,
            )
                .bind("nodeId", nodeId)
                .bind("lastSeenAt", lastSeenAt)
                .execute()
        }
    }

    private fun insertRegistration(taskKey: String, nodeId: String) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO cluster_scheduled_task_registrations (task_key, node_id)
                VALUES (:taskKey, :nodeId)
                ON CONFLICT (task_key, node_id) DO NOTHING
                """,
            )
                .bind("taskKey", taskKey)
                .bind("nodeId", nodeId)
                .execute()
        }
    }

    @TestConfiguration
    class TaskHandlerConfiguration {
        @Bean
        fun recordingClusterScheduledTaskHandler(): RecordingClusterScheduledTaskHandler = RecordingClusterScheduledTaskHandler()
    }
}

class RecordingClusterScheduledTaskHandler : ClusterScheduledTaskHandler {
    val handled = CopyOnWriteArrayList<String>()
    val failTaskKeys = CopyOnWriteArrayList<String>()

    /** taskKey -> the `currentTenantId` bound while the handler ran (absent when no principal). */
    val tenantSeen = ConcurrentHashMap<String, String>()
    override val taskType: String = TYPE

    override fun handle(task: ClusterScheduledTask) {
        handled += task.taskKey
        SecurityContext.currentOrNull()?.currentTenantId?.value?.let { tenantSeen[task.taskKey] = it }
        if (task.taskKey in failTaskKeys) {
            throw IllegalStateException("planned failure")
        }
    }

    companion object {
        const val TYPE = "recording-scheduled-test"
    }
}

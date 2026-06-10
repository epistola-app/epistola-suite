package app.epistola.suite.cluster

import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.observability.NodeIdentity
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.MutableClock
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import java.time.Duration
import java.time.OffsetDateTime

@TestPropertySource(
    properties = [
        "epistola.cluster.scheduled-tasks.poll-interval-ms=60000",
        "epistola.cluster.scheduled-tasks.lease-duration-ms=30000",
        "epistola.cluster.scheduled-tasks.retry-delay-ms=30000",
    ],
)
@Import(ClusterTestClockConfiguration::class)
class ClusterScheduledTaskRegistryIT : IntegrationTestBase() {

    @Autowired
    private lateinit var registry: ClusterScheduledTaskRegistry

    @Autowired
    private lateinit var nodeIdentity: NodeIdentity

    @Autowired
    private lateinit var jdbi: Jdbi

    @Autowired
    private lateinit var clock: MutableClock

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

        val claimed = registry.claimDue(listOf("task-claim"))

        assertThat(claimed).hasSize(1)
        assertThat(claimed.single().leaseOwnerNodeId).isEqualTo(nodeIdentity.nodeId)
        assertThat(claimed.single().attemptCount).isEqualTo(1)
    }

    @Test
    fun `complete advances the next due occurrence`() {
        seedDueTask("task-complete")
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
        clock.advanceBy(Duration.ofSeconds(61))
    }

    private fun forceDue(taskKey: String) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate("UPDATE cluster_tasks_scheduled SET next_due_at = :dueAt WHERE task_key = :taskKey")
                .bind("taskKey", taskKey)
                .bind("dueAt", now().minusSeconds(1))
                .execute()
        }
    }

    private fun now(): OffsetDateTime = OffsetDateTime.now(clock)

    private fun deleteTask(taskKey: String) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate("DELETE FROM cluster_tasks_scheduled WHERE task_key = :taskKey")
                .bind("taskKey", taskKey)
                .execute()
        }
    }
}

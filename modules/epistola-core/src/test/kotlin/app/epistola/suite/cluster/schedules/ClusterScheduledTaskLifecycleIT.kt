package app.epistola.suite.cluster.schedules

import app.epistola.suite.cluster.ClusterNodeRegistry
import app.epistola.suite.cluster.ClusterProperties
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

/**
 * Covers the retirement/purge lifecycle for code-defined scheduled tasks:
 * per-node registration tracking, orphan detection across an active fleet, soft
 * retirement, reclaim on re-registration, and purge after the retention window.
 *
 * Grace and retention windows are shrunk so the deterministic test clock can step
 * past them without large jumps.
 */
@TestPropertySource(
    properties = [
        "epistola.cluster.scheduled-tasks.reconciliation-grace-period-ms=60000",
        "epistola.cluster.scheduled-tasks.retired-retention-ms=3600000",
    ],
)
class ClusterScheduledTaskLifecycleIT : IntegrationTestBase() {

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
    private lateinit var reconciler: ClusterScheduledTaskReconciler

    @Autowired
    private lateinit var clock: Clock

    @Autowired
    private lateinit var jdbi: Jdbi

    @BeforeEach
    fun resetClock() {
        testClock.reset()
    }

    @Test
    fun `recordNodeRegistrations records this node and prunes definitions it no longer carries`() {
        val keyA = upsertTask("lifecycle-reg-a")
        val keyB = upsertTask("lifecycle-reg-b")

        registry.recordNodeRegistrations(listOf(keyA, keyB), "1.0.0")
        assertThat(registrationNodeIds(keyA)).contains(nodeIdentity.nodeId)
        assertThat(registrationNodeIds(keyB)).contains(nodeIdentity.nodeId)

        // The node restarts on a build that no longer carries keyB.
        registry.recordNodeRegistrations(listOf(keyA), "1.1.0")
        assertThat(registrationNodeIds(keyA)).contains(nodeIdentity.nodeId)
        assertThat(registrationNodeIds(keyB)).doesNotContain(nodeIdentity.nodeId)
    }

    @Test
    fun `findRetirableCodeTasks flags an orphaned code task only after the grace period`() {
        val key = upsertTask("lifecycle-orphan")
        insertRegistration(key, "ghost-node", now())

        // Within grace: not yet retirable.
        assertThat(retirableKeys()).doesNotContain(key)

        testClock.advanceBy(Duration.ofSeconds(61))

        // Past grace, and no active node vouches (ghost never heartbeats).
        assertThat(retirableKeys()).contains(key)
    }

    @Test
    fun `findRetirableCodeTasks excludes tasks an active node still vouches for`() {
        val key = upsertTask("lifecycle-vouched")
        insertRegistration(key, "ghost-node", now())
        testClock.advanceBy(Duration.ofSeconds(61))

        val retirable = registry.findRetirableCodeTasks(
            activeNodeIds = listOf("ghost-node"),
            graceBefore = now().minusSeconds(60),
        )

        assertThat(retirable.map { it.taskKey }).doesNotContain(key)
    }

    @Test
    fun `findRetirableCodeTasks never flags manual tasks`() {
        val key = upsertTask("lifecycle-manual")
        setManagementMode(key, "manual")
        testClock.advanceBy(Duration.ofSeconds(61))

        assertThat(retirableKeys()).doesNotContain(key)
    }

    @Test
    fun `retire soft-retires a code task and re-registering reclaims it`() {
        val key = upsertTask("lifecycle-retire")

        assertThat(registry.retire(key, "definition removed")).isTrue()
        val retired = registry.find(key)
        assertThat(retired?.retiredAt).isNotNull()
        assertThat(retired?.enabled).isFalse()
        assertThat(retired?.retirementReason).isEqualTo("definition removed")

        // The definition reappears in code: re-upsert clears retirement.
        registry.upsert(definition(key))
        val reclaimed = registry.find(key)
        assertThat(reclaimed?.retiredAt).isNull()
        assertThat(reclaimed?.enabled).isTrue()
        assertThat(reclaimed?.retirementReason).isNull()
    }

    @Test
    fun `purgeRetiredBefore deletes retired tasks and cascades registrations`() {
        val key = upsertTask("lifecycle-purge")
        insertRegistration(key, "ghost-node", now())
        registry.retire(key, "definition removed")
        assertThat(registrationNodeIds(key)).isNotEmpty()

        val purged = registry.purgeRetiredBefore(now().plusSeconds(1))

        assertThat(purged).isGreaterThanOrEqualTo(1)
        assertThat(registry.find(key)).isNull()
        assertThat(registrationNodeIds(key)).isEmpty()
    }

    @Test
    fun `skipNoHandler advances next due without recording failure state`() {
        val key = "lifecycle-skip"
        deleteTask(key)
        registry.upsert(definition(key))
        testClock.advanceBy(Duration.ofSeconds(61))
        nodeRegistry.heartbeat()
        val claimed = registry.claimDue(listOf(key)).single()
        val nextDueAt = now().plusMinutes(5)

        assertThat(registry.skipNoHandler(claimed.taskKey, nextDueAt)).isTrue()

        val stored = registry.find(key)
        assertThat(stored?.nextDueAt).isEqualTo(nextDueAt)
        assertThat(stored?.leaseOwnerNodeId).isNull()
        assertThat(stored?.consecutiveFailures).isZero()
        assertThat(stored?.lastError).isNull()
    }

    @Test
    fun `reconcile retires an orphaned code task while leaving vouched tasks active`() {
        // The current node is active and vouches for the real startup definitions.
        nodeRegistry.heartbeat()

        val orphanKey = upsertTask("lifecycle-reconcile-orphan")
        insertRegistration(orphanKey, "ghost-node", now())

        testClock.advanceBy(Duration.ofSeconds(61))
        nodeRegistry.heartbeat() // keep the current node active past the advance

        reconciler.reconcile()

        // The orphan (vouched only by a dead ghost node) is retired.
        val orphan = registry.find(orphanKey)
        assertThat(orphan?.retiredAt).isNotNull()
        assertThat(orphan?.enabled).isFalse()
        // The reconciler's own definition (vouched by the active current node) is untouched.
        assertThat(registry.find(ClusterScheduledTaskReconciler.TASK_KEY)?.retiredAt).isNull()
    }

    @Test
    fun `reconcile purges a task retired beyond the retention window`() {
        nodeRegistry.heartbeat()
        val key = upsertTask("lifecycle-reconcile-purge")
        registry.retire(key, "definition removed")

        testClock.advanceBy(Duration.ofHours(2)) // past the 1h test retention window
        nodeRegistry.heartbeat()

        reconciler.reconcile()

        assertThat(registry.find(key)).isNull()
    }

    private fun retirableKeys(): List<String> = registry.findRetirableCodeTasks(
        activeNodeIds = listOf(nodeIdentity.nodeId),
        graceBefore = now().minusSeconds(60),
    ).map { it.taskKey }

    private fun definition(taskKey: String): ClusterScheduledTaskDefinition = ClusterScheduledTaskDefinition(
        taskKey = taskKey,
        routingKey = "system:$taskKey",
        taskType = "test",
        schedule = ClusterScheduledTaskSchedule.FixedRate(60_000),
    )

    private fun upsertTask(prefix: String): String {
        val key = "$prefix-${UUID.randomUUID()}"
        registry.upsert(definition(key))
        return key
    }

    private fun insertRegistration(taskKey: String, nodeId: String, registeredAt: OffsetDateTime) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO cluster_scheduled_task_registrations (task_key, node_id, build_version, registered_at)
                VALUES (:taskKey, :nodeId, '1.0.0', :registeredAt)
                ON CONFLICT (task_key, node_id) DO UPDATE SET registered_at = EXCLUDED.registered_at
                """,
            )
                .bind("taskKey", taskKey)
                .bind("nodeId", nodeId)
                .bind("registeredAt", registeredAt)
                .execute()
        }
    }

    private fun registrationNodeIds(taskKey: String): List<String> = jdbi.withHandle<List<String>, Exception> { handle ->
        handle.createQuery("SELECT node_id FROM cluster_scheduled_task_registrations WHERE task_key = :taskKey ORDER BY node_id")
            .bind("taskKey", taskKey)
            .mapTo(String::class.java)
            .list()
    }

    private fun setManagementMode(taskKey: String, mode: String) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate("UPDATE cluster_tasks_scheduled SET management_mode = :mode WHERE task_key = :taskKey")
                .bind("mode", mode)
                .bind("taskKey", taskKey)
                .execute()
        }
    }

    private fun deleteTask(taskKey: String) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate("DELETE FROM cluster_tasks_scheduled WHERE task_key = :taskKey")
                .bind("taskKey", taskKey)
                .execute()
        }
    }

    private fun now(): OffsetDateTime = OffsetDateTime.now(testClock)
}

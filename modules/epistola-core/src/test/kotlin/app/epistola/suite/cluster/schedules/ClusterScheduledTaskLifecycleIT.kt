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
 * per-node registration tracking, orphan detection by node liveness
 * (`cluster_nodes.last_seen_at`), soft retirement, reclaim on re-registration,
 * and purge after the retention window.
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
    private lateinit var registrar: ClusterScheduledTaskRegistrar

    @Autowired
    private lateinit var clock: Clock

    @Autowired
    private lateinit var jdbi: Jdbi

    @BeforeEach
    fun resetClock() {
        testClock.reset()
        // Re-assert the current node's registrations for every real code
        // definition. The shared DB is mutated by other tests/classes that prune
        // this node's registrations; re-registering gives every test a clean
        // baseline where the current node vouches for the real schedules, so a
        // reconcile() in these tests never retires real tasks (no cross-test
        // pollution) and un-retires anything a prior reconcile soft-retired.
        registrar.registerDefinitions()
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
    fun `findRetirableCodeTasks flags a code task only after its node goes unseen past the grace window`() {
        val key = upsertTask("lifecycle-orphan")
        seedNode("ghost-node", now())
        insertRegistration(key, "ghost-node")

        // The ghost node was just seen: within grace, not retirable.
        assertThat(retirableKeys()).doesNotContain(key)

        // Ghost stops heartbeating; once its last_seen falls outside the grace
        // window the task it carried becomes retirable.
        testClock.advanceBy(grace().plusSeconds(1))

        assertThat(retirableKeys()).contains(key)
    }

    @Test
    fun `findRetirableCodeTasks excludes tasks a node still vouches for`() {
        val key = upsertTask("lifecycle-vouched")
        seedNode("ghost-node", now())
        insertRegistration(key, "ghost-node")

        // Even well past the grace window, a node seen recently keeps protecting.
        testClock.advanceBy(grace().plusSeconds(1))
        seedNode("ghost-node", now()) // fresh heartbeat

        assertThat(retirableKeys()).doesNotContain(key)
    }

    @Test
    fun `findRetirableCodeTasks never flags manual tasks`() {
        val key = upsertTask("lifecycle-manual")
        setManagementMode(key, "manual")
        testClock.advanceBy(grace().plusSeconds(1))

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
        seedNode("ghost-node", now())
        insertRegistration(key, "ghost-node")
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
        seedNode("ghost-node", now())
        insertRegistration(orphanKey, "ghost-node")

        testClock.advanceBy(grace().plusSeconds(1))
        nodeRegistry.heartbeat() // keep the current node fresh; ghost stays stale

        reconciler.reconcile()

        // The orphan (carried only by a now-unseen ghost node) is retired.
        val orphan = registry.find(orphanKey)
        assertThat(orphan?.retiredAt).isNotNull()
        assertThat(orphan?.enabled).isFalse()
        // The reconciler's own definition (carried by the fresh current node) is untouched.
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

    // ---- liveness gate edge cases ----------------------------------------------------------

    @Test
    fun `findRetirableCodeTasks flags a code task with no registrations at all`() {
        val key = upsertTask("lifecycle-no-reg")
        // Nothing carries it → retirable regardless of the grace window.
        assertThat(retirableKeys()).contains(key)
    }

    @Test
    fun `findRetirableCodeTasks flags a task whose registering node never heartbeated`() {
        val key = upsertTask("lifecycle-ghost-only")
        insertRegistration(key, "never-seen-node") // no cluster_nodes row for it
        assertThat(retirableKeys()).contains(key)
    }

    @Test
    fun `findRetirableCodeTasks keeps a task while any one of several nodes stays fresh`() {
        val key = upsertTask("lifecycle-multi-node")
        seedNode("node-a", now())
        seedNode("node-b", now())
        insertRegistration(key, "node-a")
        insertRegistration(key, "node-b")

        // A goes stale, B refreshed → still protected by B.
        testClock.advanceBy(grace().plusSeconds(1))
        seedNode("node-b", now())
        assertThat(retirableKeys()).doesNotContain(key)

        // Now B also goes stale → both unseen past grace → retirable.
        testClock.advanceBy(grace().plusSeconds(1))
        assertThat(retirableKeys()).contains(key)
    }

    @Test
    fun `findRetirableCodeTasks excludes an already-retired task`() {
        val key = upsertTask("lifecycle-already-retired")
        registry.retire(key, "gone")
        assertThat(retirableKeys()).doesNotContain(key)
    }

    @Test
    fun `findRetirableCodeTasks excludes a disabled but still-vouched code task`() {
        val key = upsertTask("lifecycle-disabled-vouched")
        seedNode("holder", now())
        insertRegistration(key, "holder")
        registry.disable(key)
        testClock.advanceBy(grace().plusSeconds(1))
        seedNode("holder", now()) // holder stays fresh

        // Disabled is not retired: a live holder still protects it.
        assertThat(retirableKeys()).doesNotContain(key)
        assertThat(registry.find(key)?.retiredAt).isNull()
    }

    @Test
    fun `findRetirableCodeTasks flags an orphaned each-capable-node task`() {
        val key = "lifecycle-orphan-eachnode-${UUID.randomUUID()}"
        registry.upsert(eachNodeDefinition(key))
        insertRegistration(key, "never-seen-node")
        assertThat(retirableKeys()).contains(key)
    }

    // ---- retire / retireIfOrphaned ----------------------------------------------------------

    @Test
    fun `retire is a no-op on a manual task`() {
        val key = upsertTask("lifecycle-retire-manual")
        setManagementMode(key, "manual")

        assertThat(registry.retire(key, "should not happen")).isFalse()
        val task = registry.find(key)
        assertThat(task?.retiredAt).isNull()
        assertThat(task?.enabled).isTrue()
    }

    @Test
    fun `retire is idempotent`() {
        val key = upsertTask("lifecycle-retire-idempotent")
        assertThat(registry.retire(key, "gone")).isTrue()
        assertThat(registry.retire(key, "again")).isFalse()
        assertThat(registry.find(key)?.retirementReason).isEqualTo("gone")
    }

    @Test
    fun `retire of an each-capable-node task clears its per-node state`() {
        val key = "lifecycle-retire-eachnode-${UUID.randomUUID()}"
        registry.upsert(eachNodeDefinition(key))
        insertNodeState(key, "node-a", now())
        insertNodeState(key, "node-b", now())
        assertThat(nodeStateCount(key)).isEqualTo(2)

        assertThat(registry.retire(key, "gone")).isTrue()

        assertThat(nodeStateCount(key)).isZero()
    }

    @Test
    fun `retireIfOrphaned is a no-op on a manual task`() {
        val key = upsertTask("lifecycle-orphan-manual")
        setManagementMode(key, "manual")

        assertThat(registry.retireIfOrphaned(key, now().minus(grace()), "x")).isFalse()
        assertThat(registry.find(key)?.retiredAt).isNull()
    }

    @Test
    fun `retireIfOrphaned is a no-op while a live node holds it`() {
        val key = upsertTask("lifecycle-orphan-held")
        seedNode("holder", now())
        insertRegistration(key, "holder")

        assertThat(registry.retireIfOrphaned(key, now().minus(grace()), "x")).isFalse()
        assertThat(registry.find(key)?.retiredAt).isNull()
    }

    // ---- purge ------------------------------------------------------------------------------

    @Test
    fun `purgeRetiredBefore keeps a task retired exactly at the cutoff and deletes older ones`() {
        val key = upsertTask("lifecycle-purge-boundary")
        registry.retire(key, "gone")
        val retiredAt = registry.find(key)?.retiredAt!!

        // cutoff == retired_at → kept (delete uses strict <).
        assertThat(registry.purgeRetiredBefore(retiredAt)).isZero()
        assertThat(registry.find(key)).isNotNull()

        // cutoff just after retired_at → deleted.
        assertThat(registry.purgeRetiredBefore(retiredAt.plusSeconds(1))).isGreaterThanOrEqualTo(1)
        assertThat(registry.find(key)).isNull()
    }

    @Test
    fun `purgeRetiredBefore leaves non-retired and manual tasks`() {
        val active = upsertTask("lifecycle-purge-active")
        val manual = upsertTask("lifecycle-purge-manual")
        setManagementMode(manual, "manual")

        registry.purgeRetiredBefore(now().plusYears(1))

        assertThat(registry.find(active)).isNotNull()
        assertThat(registry.find(manual)).isNotNull()
    }

    @Test
    fun `deleting a task cascades to per-node state and registrations`() {
        val key = "lifecycle-cascade-${UUID.randomUUID()}"
        registry.upsert(eachNodeDefinition(key))
        insertNodeState(key, "node-a", now())
        insertRegistration(key, "node-a")

        deleteTask(key)

        assertThat(nodeStateCount(key)).isZero()
        assertThat(registrationNodeIds(key)).isEmpty()
    }

    // ---- recordNodeRegistrations ------------------------------------------------------------

    @Test
    fun `recordNodeRegistrations with an empty set clears this node's registrations`() {
        val key = upsertTask("lifecycle-reg-empty")
        registry.recordNodeRegistrations(listOf(key), "1.0.0")
        assertThat(registrationNodeIds(key)).contains(nodeIdentity.nodeId)

        registry.recordNodeRegistrations(emptyList(), "1.0.0")
        assertThat(registrationNodeIds(key)).doesNotContain(nodeIdentity.nodeId)
    }

    @Test
    fun `recordNodeRegistrations is idempotent and refreshes build version and timestamp`() {
        val key = upsertTask("lifecycle-reg-idempotent")
        registry.recordNodeRegistrations(listOf(key), "1.0.0")
        val firstSeen = registrationRegisteredAt(key, nodeIdentity.nodeId)
        assertThat(registrationBuildVersion(key, nodeIdentity.nodeId)).isEqualTo("1.0.0")

        testClock.advanceBy(Duration.ofMinutes(1))
        registry.recordNodeRegistrations(listOf(key), "2.0.0")

        // One row per (task, node) — no duplicate.
        assertThat(registrationNodeIds(key).count { it == nodeIdentity.nodeId }).isEqualTo(1)
        assertThat(registrationBuildVersion(key, nodeIdentity.nodeId)).isEqualTo("2.0.0")
        assertThat(registrationRegisteredAt(key, nodeIdentity.nodeId)).isAfter(firstSeen)
    }

    @Test
    fun `recordNodeRegistrations accepts a null build version`() {
        val key = upsertTask("lifecycle-reg-null-version")
        registry.recordNodeRegistrations(listOf(key), null)
        assertThat(registrationNodeIds(key)).contains(nodeIdentity.nodeId)
        assertThat(registrationBuildVersion(key, nodeIdentity.nodeId)).isNull()
    }

    // ---- reconcile --------------------------------------------------------------------------

    @Test
    fun `reconcile retires multiple orphans in one pass`() {
        nodeRegistry.heartbeat()
        val orphanA = upsertTask("lifecycle-multi-orphan-a")
        val orphanB = upsertTask("lifecycle-multi-orphan-b")
        seedNode("ghost", now())
        insertRegistration(orphanA, "ghost")
        insertRegistration(orphanB, "ghost")

        testClock.advanceBy(grace().plusSeconds(1))
        nodeRegistry.heartbeat()

        reconciler.reconcile()

        assertThat(registry.find(orphanA)?.retiredAt).isNotNull()
        assertThat(registry.find(orphanB)?.retiredAt).isNotNull()
    }

    @Test
    fun `reconcile does nothing when there are no orphans`() {
        nodeRegistry.heartbeat()
        val key = upsertTask("lifecycle-reconcile-noop")
        seedNode("holder", now())
        insertRegistration(key, "holder")

        testClock.advanceBy(grace().plusSeconds(1))
        nodeRegistry.heartbeat()
        seedNode("holder", now()) // holder stays fresh → key is not an orphan

        reconciler.reconcile()

        assertThat(registry.find(key)?.retiredAt).isNull()
        assertThat(registry.find(ClusterScheduledTaskReconciler.TASK_KEY)?.retiredAt).isNull()
    }

    @Test
    fun `reconcile is idempotent`() {
        nodeRegistry.heartbeat()
        val key = upsertTask("lifecycle-reconcile-idempotent")
        seedNode("ghost", now())
        insertRegistration(key, "ghost")
        testClock.advanceBy(grace().plusSeconds(1))
        nodeRegistry.heartbeat()

        reconciler.reconcile()
        val firstRetiredAt = registry.find(key)?.retiredAt
        reconciler.reconcile()

        assertThat(firstRetiredAt).isNotNull()
        assertThat(registry.find(key)?.retiredAt).isEqualTo(firstRetiredAt)
        assertThat(registry.find(ClusterScheduledTaskReconciler.TASK_KEY)?.retiredAt).isNull()
    }

    // ---- reclaim / scope change (per-node state cleanup) ------------------------------------

    @Test
    fun `reclaiming a retired each-capable-node task starts with no stale per-node state`() {
        val key = "lifecycle-reclaim-eachnode-${UUID.randomUUID()}"
        registry.upsert(eachNodeDefinition(key))
        insertNodeState(key, "node-a", now().minusDays(1)) // stale due time
        registry.retire(key, "gone")
        assertThat(nodeStateCount(key)).isZero() // cleared on retire

        registry.upsert(eachNodeDefinition(key)) // reclaim
        assertThat(registry.find(key)?.retiredAt).isNull()
        assertThat(nodeStateCount(key)).isZero() // fresh, no inherited node state
    }

    @Test
    fun `upsert changing scope to single owner clears stale per-node state`() {
        val key = "lifecycle-scope-change-${UUID.randomUUID()}"
        registry.upsert(eachNodeDefinition(key))
        insertNodeState(key, "node-a", now())
        assertThat(nodeStateCount(key)).isEqualTo(1)

        registry.upsert(definition(key)) // definition() is SINGLE_OWNER

        assertThat(nodeStateCount(key)).isZero()
    }

    // ---- multi-node rolling deploy ----------------------------------------------------------

    @Test
    fun `a freshly heartbeating unrelated node does not protect another node's orphaned task`() {
        val key = upsertTask("lifecycle-rolling")
        seedNode("old-node", now())
        insertRegistration(key, "old-node") // only old-node carries it
        seedNode("new-node", now()) // new build is up but does not carry this task

        // Old node still fresh → retained.
        assertThat(retirableKeys()).doesNotContain(key)

        // Old node drains; new node keeps heartbeating but never registered this task.
        testClock.advanceBy(grace().plusSeconds(1))
        seedNode("new-node", now())
        assertThat(retirableKeys()).contains(key)
    }

    private fun grace(): Duration = Duration.ofMillis(properties.scheduledTasks.reconciliationGracePeriodMs)

    private fun retirableKeys(): List<String> = registry.findRetirableCodeTasks(now().minus(grace())).map { it.taskKey }

    private fun definition(taskKey: String): ClusterScheduledTaskDefinition = ClusterScheduledTaskDefinition(
        taskKey = taskKey,
        routingKey = "system:$taskKey",
        taskType = "test",
        schedule = ClusterScheduledTaskSchedule.FixedRate(60_000),
    )

    private fun eachNodeDefinition(taskKey: String): ClusterScheduledTaskDefinition = ClusterScheduledTaskDefinition(
        taskKey = taskKey,
        routingKey = "system:$taskKey",
        taskType = "test",
        schedule = ClusterScheduledTaskSchedule.FixedRate(60_000),
        executionScope = ClusterScheduledTaskExecutionScope.EACH_CAPABLE_NODE,
    )

    private fun upsertTask(prefix: String): String {
        val key = "$prefix-${UUID.randomUUID()}"
        registry.upsert(definition(key))
        return key
    }

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
                INSERT INTO cluster_scheduled_task_registrations (task_key, node_id, build_version, registered_at)
                VALUES (:taskKey, :nodeId, '1.0.0', :registeredAt)
                ON CONFLICT (task_key, node_id) DO UPDATE SET registered_at = EXCLUDED.registered_at
                """,
            )
                .bind("taskKey", taskKey)
                .bind("nodeId", nodeId)
                .bind("registeredAt", now())
                .execute()
        }
    }

    private fun registrationNodeIds(taskKey: String): List<String> = jdbi.withHandle<List<String>, Exception> { handle ->
        handle.createQuery("SELECT node_id FROM cluster_scheduled_task_registrations WHERE task_key = :taskKey ORDER BY node_id")
            .bind("taskKey", taskKey)
            .mapTo(String::class.java)
            .list()
    }

    private fun registrationBuildVersion(taskKey: String, nodeId: String): String? = jdbi.withHandle<String?, Exception> { handle ->
        handle.createQuery("SELECT build_version FROM cluster_scheduled_task_registrations WHERE task_key = :taskKey AND node_id = :nodeId")
            .bind("taskKey", taskKey)
            .bind("nodeId", nodeId)
            .mapTo(String::class.java)
            .findOne()
            .orElse(null)
    }

    private fun registrationRegisteredAt(taskKey: String, nodeId: String): OffsetDateTime = jdbi.withHandle<OffsetDateTime, Exception> { handle ->
        handle.createQuery("SELECT registered_at FROM cluster_scheduled_task_registrations WHERE task_key = :taskKey AND node_id = :nodeId")
            .bind("taskKey", taskKey)
            .bind("nodeId", nodeId)
            .mapTo(OffsetDateTime::class.java)
            .one()
    }

    private fun insertNodeState(taskKey: String, nodeId: String, nextDueAt: OffsetDateTime) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO cluster_tasks_scheduled_node_state (task_key, node_id, next_due_at)
                VALUES (:taskKey, :nodeId, :nextDueAt)
                ON CONFLICT (task_key, node_id) DO UPDATE SET next_due_at = EXCLUDED.next_due_at
                """,
            )
                .bind("taskKey", taskKey)
                .bind("nodeId", nodeId)
                .bind("nextDueAt", nextDueAt)
                .execute()
        }
    }

    private fun nodeStateCount(taskKey: String): Int = jdbi.withHandle<Int, Exception> { handle ->
        handle.createQuery("SELECT count(*) FROM cluster_tasks_scheduled_node_state WHERE task_key = :taskKey")
            .bind("taskKey", taskKey)
            .mapTo(Int::class.java)
            .one()
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

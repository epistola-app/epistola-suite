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
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Covers the simplified scheduled-task cleanup: each node records the schedules it
 * carries (`cluster_scheduled_task_registrations`); a task no live node carries
 * within the grace window is **deleted** (no soft-retire/purge). A returning
 * definition is simply re-created fresh by the registrar.
 *
 * Grace is shrunk so the deterministic test clock can step past it. Because
 * `deleteOrphanedTasks` is destructive, delete-path tests heartbeat the current
 * node first so the real startup definitions (vouched by the current node in
 * `@BeforeEach`) stay protected and only the test's deliberate orphan is removed.
 */
@TestPropertySource(
    properties = ["epistola.cluster.scheduled-tasks.reconciliation-grace-period-ms=60000"],
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
    private lateinit var reconciler: ClusterScheduledTaskReconciler

    @Autowired
    private lateinit var registrar: ClusterScheduledTaskRegistrar

    @Autowired
    private lateinit var jdbi: Jdbi

    @BeforeEach
    fun resetClock() {
        testClock.reset()
        // Re-assert the current node's registrations for every real definition, so a
        // delete-path test that heartbeats the current node never removes a real task.
        registrar.registerDefinitions()
    }

    // ---- registration tracking --------------------------------------------------------------

    @Test
    fun `recordNodeRegistrations records this node and prunes definitions it no longer carries`() {
        val keyA = upsertTask("lifecycle-reg-a")
        val keyB = upsertTask("lifecycle-reg-b")

        registry.recordNodeRegistrations(listOf(keyA, keyB))
        assertThat(registrationNodeIds(keyA)).contains(nodeIdentity.nodeId)
        assertThat(registrationNodeIds(keyB)).contains(nodeIdentity.nodeId)

        // The node restarts on a build that no longer carries keyB.
        registry.recordNodeRegistrations(listOf(keyA))
        assertThat(registrationNodeIds(keyA)).contains(nodeIdentity.nodeId)
        assertThat(registrationNodeIds(keyB)).doesNotContain(nodeIdentity.nodeId)
    }

    @Test
    fun `recordNodeRegistrations with an empty set clears this node's registrations`() {
        val key = upsertTask("lifecycle-reg-empty")
        registry.recordNodeRegistrations(listOf(key))
        assertThat(registrationNodeIds(key)).contains(nodeIdentity.nodeId)

        registry.recordNodeRegistrations(emptyList())
        assertThat(registrationNodeIds(key)).doesNotContain(nodeIdentity.nodeId)
    }

    @Test
    fun `registerAll records this node as vouching for each definition`() {
        val keyA = "lifecycle-registerall-a-${UUID.randomUUID()}"
        val keyB = "lifecycle-registerall-b-${UUID.randomUUID()}"

        registry.registerAll(listOf(definition(keyA), definition(keyB)))

        assertThat(registrationNodeIds(keyA)).contains(nodeIdentity.nodeId)
        assertThat(registrationNodeIds(keyB)).contains(nodeIdentity.nodeId)
    }

    // ---- orphan detection (deleteOrphanedTasks) ---------------------------------------------

    @Test
    fun `deleteOrphanedTasks removes a task with no registrations at all`() {
        nodeRegistry.heartbeat()
        val key = upsertTask("lifecycle-no-reg")

        val deleted = registry.deleteOrphanedTasks(seenSince())

        assertThat(deleted).contains(key)
        assertThat(registry.find(key)).isNull()
    }

    @Test
    fun `deleteOrphanedTasks removes a task whose registering node never heartbeated`() {
        nodeRegistry.heartbeat()
        val key = upsertTask("lifecycle-ghost-only")
        insertRegistration(key, "never-seen-node") // no cluster_nodes row

        assertThat(registry.deleteOrphanedTasks(seenSince())).contains(key)
        assertThat(registry.find(key)).isNull()
    }

    @Test
    fun `deleteOrphanedTasks removes a task only after its node goes unseen past the grace window`() {
        nodeRegistry.heartbeat()
        val key = upsertTask("lifecycle-orphan")
        seedNode("ghost-node", now())
        insertRegistration(key, "ghost-node")

        // Within grace: the carrying node was just seen → not deleted.
        assertThat(registry.deleteOrphanedTasks(seenSince())).doesNotContain(key)
        assertThat(registry.find(key)).isNotNull()

        // Past grace: the node is unseen → deleted.
        testClock.advanceBy(grace().plusSeconds(1))
        nodeRegistry.heartbeat()
        assertThat(registry.deleteOrphanedTasks(seenSince())).contains(key)
        assertThat(registry.find(key)).isNull()
    }

    @Test
    fun `deleteOrphanedTasks keeps a task while any one of several nodes stays fresh`() {
        nodeRegistry.heartbeat()
        val key = upsertTask("lifecycle-multi-node")
        seedNode("node-a", now())
        seedNode("node-b", now())
        insertRegistration(key, "node-a")
        insertRegistration(key, "node-b")

        testClock.advanceBy(grace().plusSeconds(1))
        nodeRegistry.heartbeat()
        seedNode("node-b", now()) // b refreshed, a stale
        assertThat(registry.deleteOrphanedTasks(seenSince())).doesNotContain(key)

        testClock.advanceBy(grace().plusSeconds(1))
        nodeRegistry.heartbeat() // both ghosts now stale
        assertThat(registry.deleteOrphanedTasks(seenSince())).contains(key)
    }

    @Test
    fun `deleteOrphanedTasks does not remove a disabled but still-vouched task`() {
        nodeRegistry.heartbeat()
        val key = upsertTask("lifecycle-disabled-vouched")
        seedNode("holder", now())
        insertRegistration(key, "holder")
        registry.disable(key)
        testClock.advanceBy(grace().plusSeconds(1))
        nodeRegistry.heartbeat()
        seedNode("holder", now()) // holder stays fresh

        assertThat(registry.deleteOrphanedTasks(seenSince())).doesNotContain(key)
        assertThat(registry.find(key)).isNotNull()
    }

    @Test
    fun `deleteOrphanedTasks removes an orphaned each-capable-node task and its per-node state`() {
        nodeRegistry.heartbeat()
        val key = "lifecycle-orphan-eachnode-${UUID.randomUUID()}"
        registry.upsert(eachNodeDefinition(key))
        insertNodeState(key, "node-a", now())
        insertRegistration(key, "never-seen-node")

        assertThat(registry.deleteOrphanedTasks(seenSince())).contains(key)
        assertThat(registry.find(key)).isNull()
        assertThat(nodeStateCount(key)).isZero() // cascaded
    }

    @Test
    fun `deleteOrphanedTasks removes an orphaned tenant-scoped task`() {
        nodeRegistry.heartbeat()
        val tenant = createTenant("Lifecycle Orphan Tenant")
        val key = "lifecycle-tenant-orphan-${UUID.randomUUID()}"
        registry.upsert(
            ClusterScheduledTaskDefinition(
                taskKey = key,
                tenantKey = tenant.id,
                routingKey = tenant.id.value,
                taskType = "test",
                schedule = ClusterScheduledTaskSchedule.FixedRate(60_000),
            ),
        )

        assertThat(registry.deleteOrphanedTasks(seenSince())).contains(key)
        assertThat(registry.find(key)).isNull()
    }

    @Test
    fun `deleteIfOrphaned deletes an orphan and is a no-op while a live node carries it`() {
        nodeRegistry.heartbeat()
        val held = upsertTask("lifecycle-held")
        seedNode("holder", now())
        insertRegistration(held, "holder")
        assertThat(registry.deleteIfOrphaned(held, seenSince())).isFalse()
        assertThat(registry.find(held)).isNotNull()

        val orphan = upsertTask("lifecycle-orphan-single")
        assertThat(registry.deleteIfOrphaned(orphan, seenSince())).isTrue()
        assertThat(registry.find(orphan)).isNull()
    }

    // ---- reconcile --------------------------------------------------------------------------

    @Test
    fun `reconcile deletes orphaned tasks while leaving vouched tasks (and itself) intact`() {
        nodeRegistry.heartbeat()
        val orphanA = upsertTask("lifecycle-reconcile-orphan-a")
        val orphanB = upsertTask("lifecycle-reconcile-orphan-b")
        seedNode("ghost", now())
        insertRegistration(orphanA, "ghost")
        insertRegistration(orphanB, "ghost")

        testClock.advanceBy(grace().plusSeconds(1))
        nodeRegistry.heartbeat() // current node fresh; ghost stale

        reconciler.reconcile()

        assertThat(registry.find(orphanA)).isNull()
        assertThat(registry.find(orphanB)).isNull()
        // The reconciler's own definition (carried by the fresh current node) survives.
        assertThat(registry.find(ClusterScheduledTaskReconciler.TASK_KEY)).isNotNull()
    }

    @Test
    fun `reconcile does nothing when there are no orphans`() {
        nodeRegistry.heartbeat()
        val key = upsertTask("lifecycle-reconcile-noop")
        seedNode("holder", now())
        insertRegistration(key, "holder")

        testClock.advanceBy(grace().plusSeconds(1))
        nodeRegistry.heartbeat()
        seedNode("holder", now()) // holder stays fresh

        reconciler.reconcile()

        assertThat(registry.find(key)).isNotNull()
        assertThat(registry.find(ClusterScheduledTaskReconciler.TASK_KEY)).isNotNull()
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
        reconciler.reconcile()

        assertThat(registry.find(key)).isNull()
        assertThat(registry.find(ClusterScheduledTaskReconciler.TASK_KEY)).isNotNull()
    }

    @Test
    fun `registerAll vouches for a re-created task so reconcile does not delete it`() {
        nodeRegistry.heartbeat()
        val key = "lifecycle-recreate-${UUID.randomUUID()}"

        // A returning definition is re-created and vouched atomically by the registrar.
        registry.registerAll(listOf(definition(key)))
        assertThat(registry.find(key)).isNotNull()
        assertThat(registrationNodeIds(key)).contains(nodeIdentity.nodeId)

        testClock.advanceBy(grace().plusSeconds(1))
        nodeRegistry.heartbeat()
        reconciler.reconcile()

        assertThat(registry.find(key)).isNotNull()
    }

    // ---- cascade & scope change -------------------------------------------------------------

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

    @Test
    fun `upsert changing scope to single owner clears stale per-node state`() {
        val key = "lifecycle-scope-change-${UUID.randomUUID()}"
        registry.upsert(eachNodeDefinition(key))
        insertNodeState(key, "node-a", now())
        assertThat(nodeStateCount(key)).isEqualTo(1)

        registry.upsert(definition(key)) // definition() is SINGLE_OWNER

        assertThat(nodeStateCount(key)).isZero()
    }

    // ---- rolling deploy ---------------------------------------------------------------------

    @Test
    fun `a freshly heartbeating unrelated node does not protect another node's orphaned task`() {
        nodeRegistry.heartbeat()
        val key = upsertTask("lifecycle-rolling")
        seedNode("old-node", now())
        insertRegistration(key, "old-node") // only old-node carries it
        seedNode("new-node", now()) // new build up, does not carry this task

        assertThat(registry.deleteOrphanedTasks(seenSince())).doesNotContain(key) // old-node fresh

        testClock.advanceBy(grace().plusSeconds(1))
        nodeRegistry.heartbeat()
        seedNode("new-node", now()) // new-node still fresh, old-node drained
        assertThat(registry.deleteOrphanedTasks(seenSince())).contains(key)
    }

    private fun grace(): Duration = Duration.ofMillis(properties.scheduledTasks.reconciliationGracePeriodMs)

    private fun seenSince(): OffsetDateTime = now().minus(grace())

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

    private fun registrationNodeIds(taskKey: String): List<String> = jdbi.withHandle<List<String>, Exception> { handle ->
        handle.createQuery("SELECT node_id FROM cluster_scheduled_task_registrations WHERE task_key = :taskKey ORDER BY node_id")
            .bind("taskKey", taskKey)
            .mapTo(String::class.java)
            .list()
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

    private fun deleteTask(taskKey: String) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate("DELETE FROM cluster_tasks_scheduled WHERE task_key = :taskKey")
                .bind("taskKey", taskKey)
                .execute()
        }
    }

    private fun now(): OffsetDateTime = OffsetDateTime.now(testClock)
}

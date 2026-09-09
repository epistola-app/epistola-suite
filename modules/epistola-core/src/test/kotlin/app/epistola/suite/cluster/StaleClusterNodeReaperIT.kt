// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.cluster

import app.epistola.suite.cluster.schedules.ClusterScheduledTaskExecutionScope
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskReconciler
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskRegistry
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskScheduleKind
import app.epistola.suite.observability.NodeIdentity
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import java.time.Duration
import java.time.OffsetDateTime

/**
 * The node registry only ever grew: `node_id` is the pod hostname, so every rollout
 * registered a fresh set of rows and nothing removed them.
 *
 * Two environment facts shape these tests:
 *
 * 1. `MutableClock` starts at a fixed instant unrelated to wall-clock time, while the
 *    application's startup heartbeat wrote `last_seen_at` from the system clock. Every
 *    seeded age is therefore computed from [now], never `OffsetDateTime.now()`.
 * 2. The reaper reads [ClusterProperties.effectiveStaleNodeRetention], which clamps the
 *    configured window up to 4x the reconciliation grace period. The retention configured
 *    below is far above that floor, so it is used verbatim.
 */
@TestPropertySource(
    properties = [
        "epistola.cluster.node-reaper.stale-node-retention=P7D",
        // Per-minute, so the poll test needs only a small clock move; a daily cron would
        // require a ~24h jump that makes every seeded node look ancient.
        "epistola.cluster.node-reaper.cron=0 * * * * *",
    ],
)
class StaleClusterNodeReaperIT : IntegrationTestBase() {

    @Autowired
    private lateinit var reaper: StaleClusterNodeReaper

    @Autowired
    private lateinit var registry: ClusterNodeRegistry

    @Autowired
    private lateinit var scheduledTaskRegistry: ClusterScheduledTaskRegistry

    @Autowired
    private lateinit var reconciler: ClusterScheduledTaskReconciler

    @Autowired
    private lateinit var properties: ClusterProperties

    @Autowired
    private lateinit var nodeIdentity: NodeIdentity

    @Autowired
    private lateinit var jdbi: Jdbi

    @AfterEach
    fun restoreCurrentNode() {
        // Several tests age the current node's row into the past. Leave it live so a
        // later test in this context can still claim work.
        registry.heartbeat()
        registry.recordPollCompleted()
    }

    @Test
    fun `registers itself as a single-owner clustered scheduled task`() {
        val task = scheduledTaskRegistry.find(StaleClusterNodeReaper.TASK_KEY)

        assertThat(task).isNotNull()
        assertThat(task!!.routingKey).isEqualTo(StaleClusterNodeReaper.ROUTING_KEY)
        assertThat(task.taskType).isEqualTo(StaleClusterNodeReaper.TASK_TYPE)
        assertThat(task.executionScope).isEqualTo(ClusterScheduledTaskExecutionScope.SINGLE_OWNER)
        assertThat(task.scheduleKind).isEqualTo(ClusterScheduledTaskScheduleKind.CRON)
        // Proves the configured cron reaches the definition rather than a hardcoded default.
        assertThat(task.cronExpression).isEqualTo("0 * * * * *")
    }

    @Test
    fun `purges nodes whose heartbeat is older than the retention window`() {
        insertNode("reaper-it-ancient", now().minusDays(30))
        insertNode("reaper-it-just-stale", now().minusDays(8))

        val purge = reaper.deleteStaleNodes()

        assertThat(purge.nodes).isEqualTo(2)
        assertThat(nodeExists("reaper-it-ancient")).isFalse()
        assertThat(nodeExists("reaper-it-just-stale")).isFalse()
    }

    @Test
    fun `keeps a node inside the retention window`() {
        insertNode("reaper-it-recent", now().minusDays(6))

        val purge = reaper.deleteStaleNodes()

        assertThat(purge.nodes).isZero()
        assertThat(nodeExists("reaper-it-recent")).isTrue()
    }

    @Test
    fun `never purges the current node however stale its heartbeat looks`() {
        // Every claim path requires this node's own row to exist and be fresh, so a node
        // that deleted itself would silently stop claiming all cluster work.
        insertNode(nodeIdentity.nodeId, now().minusDays(30))

        reaper.deleteStaleNodes()

        assertThat(nodeExists(nodeIdentity.nodeId)).isTrue()
        assertThat(registry.currentNode()).isNotNull()
    }

    @Test
    fun `purges the dead node's scheduled-task registrations and per-node state`() {
        val taskKey = "test.stale-node-reaper-cascade"
        insertScheduledTask(taskKey)
        insertNode("reaper-it-cascade", now().minusDays(30))
        insertRegistration(taskKey, "reaper-it-cascade")
        insertNodeState(taskKey, "reaper-it-cascade", now())

        try {
            val purge = reaper.deleteStaleNodes()

            assertThat(nodeExists("reaper-it-cascade")).isFalse()
            assertThat(countRegistrations("reaper-it-cascade")).isZero()
            assertThat(countNodeStates("reaper-it-cascade")).isZero()
            assertThat(purge.registrations).isEqualTo(1)
            assertThat(purge.nodeStates).isEqualTo(1)
            // The cascade removes the dead node's rows, never the task definition itself.
            assertThat(scheduledTaskRegistry.find(taskKey)).isNotNull()
        } finally {
            deleteScheduledTask(taskKey)
        }
    }

    @Test
    fun `leaves a live node's registrations untouched`() {
        val before = countRegistrations(nodeIdentity.nodeId)
        insertNode("reaper-it-scoped", now().minusDays(30))

        reaper.deleteStaleNodes()

        // Proves the cascade joins the purged set rather than deleting by predicate.
        assertThat(countRegistrations(nodeIdentity.nodeId)).isEqualTo(before)
    }

    @Test
    fun `keeps a node stale far beyond the reconciliation grace period`() {
        // The regression this whole design turns on. A node row is what vouches for that
        // node's scheduled-task definitions (LIVE_REGISTRATION_EXISTS inner-joins to
        // cluster_nodes), so purging inside the reconciler's grace window would turn a
        // routine pod restart into deleted schedules.
        val gracePeriod = Duration.ofMillis(properties.scheduledTasks.reconciliationGracePeriodMs)
        val staleFor = gracePeriod.multipliedBy(8)
        assertThat(staleFor).isLessThan(properties.effectiveStaleNodeRetention())

        val taskKey = "test.stale-node-reaper-restart"
        insertScheduledTask(taskKey)
        insertNode("reaper-it-restarting", now().minus(staleFor))
        insertRegistration(taskKey, "reaper-it-restarting")

        try {
            reaper.deleteStaleNodes()

            assertThat(nodeExists("reaper-it-restarting")).isTrue()
            assertThat(countRegistrations("reaper-it-restarting")).isEqualTo(1)
        } finally {
            deleteScheduledTask(taskKey)
        }
    }

    @Test
    fun `a purged node stops protecting its definitions, which is why retention outlives the grace period`() {
        // Documents the coupling directly: once the reaper does remove a node, that node's
        // registration no longer vouches and the reconciler retires the definition. Safe
        // only because retention is days while the grace period is minutes.
        val taskKey = "test.stale-node-reaper-orphan"
        insertScheduledTask(taskKey)
        insertNode("reaper-it-orphan", now().minusDays(30))
        insertRegistration(taskKey, "reaper-it-orphan")

        try {
            reaper.deleteStaleNodes()
            assertThat(countRegistrations("reaper-it-orphan")).isZero()

            reconciler.reconcile()

            assertThat(scheduledTaskRegistry.find(taskKey)).isNull()
        } finally {
            deleteScheduledTask(taskKey)
        }
    }

    @Test
    fun `forgetting a node removes it immediately with the same cascade`() {
        val taskKey = "test.stale-node-reaper-forget"
        insertScheduledTask(taskKey)
        insertNode("reaper-it-forget", now().minusMinutes(30))
        insertRegistration(taskKey, "reaper-it-forget")

        try {
            val purge = registry.forgetNode("reaper-it-forget")

            assertThat(purge.nodes).isEqualTo(1)
            assertThat(purge.registrations).isEqualTo(1)
            assertThat(nodeExists("reaper-it-forget")).isFalse()
        } finally {
            deleteScheduledTask(taskKey)
        }
    }

    @Test
    fun `forgetting refuses a node that is still heartbeating`() {
        insertNode("reaper-it-live", now())

        val purge = registry.forgetNode("reaper-it-live")

        assertThat(purge.nodes).isZero()
        assertThat(nodeExists("reaper-it-live")).isTrue()
    }

    /**
     * The guard that separates "stale" from "forgettable".
     *
     * A node lagging its heartbeat reads `stale` after `idle-timeout-ms` (10s), but it may
     * simply be slow — under database pressure a live node can miss several beats. Its
     * `cluster_scheduled_task_registrations` rows are only ever written at startup
     * (`ClusterScheduledTaskRegistrar`, on `ApplicationReadyEvent`); the heartbeat touches
     * only `cluster_nodes`. So forgetting it here would strip registrations the node
     * cannot restore while running, and the reconciler would then hard-delete any
     * definition only it carried.
     */
    @Test
    fun `forgetting refuses a node that is stale but not yet unseen for the grace period`() {
        val taskKey = "test.stale-node-reaper-lagging"
        insertScheduledTask(taskKey)
        // Well past idle-timeout-ms (10s), well inside reconciliation-grace-period-ms (15m).
        insertNode("reaper-it-lagging", now().minusMinutes(2))
        insertRegistration(taskKey, "reaper-it-lagging")

        try {
            val purge = registry.forgetNode("reaper-it-lagging")

            assertThat(purge.nodes).isZero()
            assertThat(nodeExists("reaper-it-lagging")).isTrue()
            // The registrations are the thing actually being protected here.
            assertThat(countRegistrations("reaper-it-lagging")).isEqualTo(1)
        } finally {
            deleteScheduledTask(taskKey)
        }
    }

    @Test
    fun `forgetting accepts a node once it passes the grace period`() {
        insertNode("reaper-it-forgettable", now().minusMinutes(16))

        val purge = registry.forgetNode("reaper-it-forgettable")

        assertThat(purge.nodes).isEqualTo(1)
        assertThat(nodeExists("reaper-it-forgettable")).isFalse()
    }

    @Test
    fun `forgetting refuses the current node`() {
        // Even aged past the idle timeout, this node must survive — it is about to claim work.
        insertNode(nodeIdentity.nodeId, now().minusDays(30))

        val purge = registry.forgetNode(nodeIdentity.nodeId)

        assertThat(purge.nodes).isZero()
        assertThat(nodeExists(nodeIdentity.nodeId)).isTrue()
    }

    private fun now(): OffsetDateTime = OffsetDateTime.now(testClock)

    private fun nodeExists(nodeId: String): Boolean = jdbi.withHandle<Boolean, Exception> { handle ->
        handle.createQuery("SELECT EXISTS (SELECT 1 FROM cluster_nodes WHERE node_id = :nodeId)")
            .bind("nodeId", nodeId)
            .mapTo(Boolean::class.java)
            .one()
    }

    private fun countRegistrations(nodeId: String): Int = jdbi.withHandle<Int, Exception> { handle ->
        handle.createQuery("SELECT count(*) FROM cluster_scheduled_task_registrations WHERE node_id = :nodeId")
            .bind("nodeId", nodeId)
            .mapTo(Int::class.java)
            .one()
    }

    private fun countNodeStates(nodeId: String): Int = jdbi.withHandle<Int, Exception> { handle ->
        handle.createQuery("SELECT count(*) FROM cluster_tasks_scheduled_node_state WHERE node_id = :nodeId")
            .bind("nodeId", nodeId)
            .mapTo(Int::class.java)
            .one()
    }

    private fun insertNode(nodeId: String, lastSeenAt: OffsetDateTime) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO cluster_nodes (node_id, capabilities, joined_at, last_seen_at, metadata)
                VALUES (:nodeId, '["suite"]'::jsonb, :joinedAt, :lastSeenAt, '{}'::jsonb)
                ON CONFLICT (node_id) DO UPDATE SET last_seen_at = EXCLUDED.last_seen_at
                """,
            )
                .bind("nodeId", nodeId)
                .bind("joinedAt", lastSeenAt)
                .bind("lastSeenAt", lastSeenAt)
                .execute()
        }
    }

    /** Both cascade tables FK `task_key`, so a real definition row must exist first. */
    private fun insertScheduledTask(taskKey: String) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO cluster_tasks_scheduled (
                    task_key, routing_key, task_type, required_capability, payload,
                    schedule_kind, interval_ms, enabled, next_due_at, execution_scope
                )
                VALUES (
                    :taskKey, :routingKey, :taskType, 'suite', '{}'::jsonb,
                    'fixed_delay', 60000, true, :nextDueAt, 'single_owner'
                )
                """,
            )
                .bind("taskKey", taskKey)
                .bind("routingKey", "system:$taskKey")
                .bind("taskType", taskKey)
                .bind("nextDueAt", now().plusDays(1))
                .execute()
        }
    }

    private fun deleteScheduledTask(taskKey: String) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate("DELETE FROM cluster_tasks_scheduled WHERE task_key = :taskKey")
                .bind("taskKey", taskKey)
                .execute()
        }
    }

    private fun insertRegistration(taskKey: String, nodeId: String) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO cluster_scheduled_task_registrations (task_key, node_id)
                VALUES (:taskKey, :nodeId)
                ON CONFLICT DO NOTHING
                """,
            )
                .bind("taskKey", taskKey)
                .bind("nodeId", nodeId)
                .execute()
        }
    }

    private fun insertNodeState(taskKey: String, nodeId: String, nextDueAt: OffsetDateTime) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO cluster_tasks_scheduled_node_state (task_key, node_id, next_due_at)
                VALUES (:taskKey, :nodeId, :nextDueAt)
                """,
            )
                .bind("taskKey", taskKey)
                .bind("nodeId", nodeId)
                .bind("nextDueAt", nextDueAt)
                .execute()
        }
    }
}

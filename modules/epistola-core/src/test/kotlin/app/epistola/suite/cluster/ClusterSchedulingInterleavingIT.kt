// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.cluster

import app.epistola.suite.cluster.schedules.ClusterScheduledTaskDefinition
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskSchedule
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskScheduleCalculator
import app.epistola.suite.cluster.timers.ClusterTimerOwnership
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.OffsetDateTime

/**
 * Interleavings of several cluster nodes that a wall-clock cluster produces only by accident: a run
 * held open while time passes, an operator acting mid-run, a node whose poll thread is stuck. Each
 * test drives [VirtualCluster] nodes by hand, so the interleaving is exact and repeatable.
 */
// Contends for installation-wide cluster rows from several node identities; needs its own database.
@TestPropertySource(properties = ["epistola.test.private-context=cluster-scheduling-interleaving"])
class ClusterSchedulingInterleavingIT : IntegrationTestBase() {
    @Autowired private lateinit var jdbi: Jdbi

    @Autowired private lateinit var objectMapper: ObjectMapper

    @Autowired private lateinit var properties: ClusterProperties

    @Autowired private lateinit var calculator: ClusterScheduledTaskScheduleCalculator

    @Autowired private lateinit var ownership: ClusterTimerOwnership

    private lateinit var cluster: VirtualCluster

    @BeforeEach
    fun setUp() {
        testClock.reset()
        forgetVirtualNodes()
        cluster = VirtualCluster(jdbi, objectMapper, properties, calculator, ownership, mediator, testClock)
    }

    @AfterEach
    fun tearDown() {
        cluster.close()
    }

    @Test
    fun `a task waiting in a claimed batch is not run twice when the run before it outlasts the lease`() {
        val a = cluster.node("node-a")
        a.register(task("batch-1"), task("batch-2"))
        testClock.advanceBy(Duration.ofSeconds(61))

        // A claims both tasks in one batch and is held on whichever it dispatches first.
        cluster.holdNextRun()
        a.pollTasksAsync()
        val first = cluster.awaitHeld()
        val waiting = if (first == "batch-1") "batch-2" else "batch-1"

        // The first run outlasts the lease. A's renewer keeps renewing what it is running.
        testClock.advanceBy(Duration.ofSeconds(31))
        a.heartbeat()
        a.renew(first)

        val b = cluster.node("node-b")
        b.register(task("batch-1"), task("batch-2"))
        b.pollTasks()

        cluster.releaseHeld()
        cluster.joinAll()

        assertThat(cluster.runsOf(waiting)).hasSize(1)
    }

    @Test
    fun `a timer waiting in a claimed batch is not run twice when the run before it outlasts the lease`() {
        val a = cluster.node("node-a")
        a.heartbeat()
        // B, once it is up, owns both; until then A is the only node and claims them.
        val ownedByB = routingKeysOwnedBy("node-b", over = "node-a").take(2).toList()
        schedule(a, "timer-batch-1", dueAt = now(), routingKey = ownedByB[0])
        schedule(a, "timer-batch-2", dueAt = now(), routingKey = ownedByB[1])

        cluster.holdNextRun()
        a.pollTimersAsync()
        val first = cluster.awaitHeld()
        val waiting = if (first == "timer-batch-1") "timer-batch-2" else "timer-batch-1"

        testClock.advanceBy(Duration.ofSeconds(31))
        a.heartbeat()
        a.renew(first)

        val b = cluster.node("node-b")
        b.heartbeat()
        b.pollTimers()

        cluster.releaseHeld()
        cluster.joinAll()

        assertThat(cluster.runsOf(waiting)).hasSize(1)
    }

    @Test
    fun `triggering a running task does not let another node run it at the same time`() {
        val a = cluster.node("node-a")
        a.register(task("trigger-running"))
        testClock.advanceBy(Duration.ofSeconds(61))

        cluster.holdNextRun()
        a.pollTasksAsync()
        cluster.awaitHeld()

        // An operator presses "run now" while A is still running it.
        a.tasks.triggerNow("trigger-running")

        val b = cluster.node("node-b")
        b.register(task("trigger-running"))
        b.pollTasks()

        cluster.releaseHeld()
        cluster.joinAll()

        assertThat(cluster.overlaps).isEmpty()
    }

    @Test
    fun `disabling and enabling a running task does not let another node run it at the same time`() {
        val a = cluster.node("node-a")
        a.register(task("toggle-running"))
        testClock.advanceBy(Duration.ofSeconds(61))

        cluster.holdNextRun()
        a.pollTasksAsync()
        cluster.awaitHeld()

        a.tasks.disable("toggle-running")
        a.tasks.enable("toggle-running")

        val b = cluster.node("node-b")
        b.register(task("toggle-running"))
        b.pollTasks()

        cluster.releaseHeld()
        cluster.joinAll()

        assertThat(cluster.overlaps).isEmpty()
    }

    @Test
    fun `a run that finishes after its task was disabled still records its completion`() {
        val a = cluster.node("node-a")
        a.register(task("disable-running"))
        testClock.advanceBy(Duration.ofSeconds(61))

        cluster.holdNextRun()
        a.pollTasksAsync()
        cluster.awaitHeld()
        a.tasks.disable("disable-running")
        cluster.releaseHeld()
        cluster.joinAll()

        val task = a.tasks.find("disable-running")!!
        assertThat(task.lastCompletedAt).isNotNull()
        assertThat(task.nextDueAt).isAfter(now())
    }

    @Test
    fun `a timer owned by a node whose timer poll is stuck is still run by a healthy node`() {
        val a = cluster.node("node-a")
        val b = cluster.node("node-b")
        a.heartbeat()
        b.heartbeat()
        val ownedByA = routingKeysOwnedBy("node-a", over = "node-b").take(2).toList()

        // A's timer poll gets stuck in a handler that never returns.
        schedule(a, "stuck", dueAt = now(), routingKey = ownedByA[0])
        cluster.holdNextRun()
        a.pollTimersAsync()
        assertThat(cluster.awaitHeld()).isEqualTo("stuck")

        // A second timer for A falls due. A keeps heartbeating, renewing its stuck run, and its
        // scheduled-task poll (a different thread) keeps completing.
        schedule(a, "behind-stuck", dueAt = now().plusMinutes(1), routingKey = ownedByA[1])
        repeat(30) {
            testClock.advanceBy(Duration.ofMinutes(1))
            a.heartbeat()
            a.renew("stuck")
            a.nodes.recordPollCompleted()
            b.heartbeat()
            b.pollTimers()
        }

        assertThat(cluster.runsOf("behind-stuck")).isNotEmpty()
    }

    private fun task(key: String) = ClusterScheduledTaskDefinition(
        taskKey = key,
        routingKey = "monkey:$key",
        taskType = VirtualCluster.TASK_TYPE,
        schedule = ClusterScheduledTaskSchedule.FixedRate(60_000),
        requiredCapability = VirtualCluster.CAPABILITY,
    )

    private fun schedule(node: VirtualCluster.VirtualNode, key: String, dueAt: OffsetDateTime, routingKey: String = "monkey:$key") {
        node.timers.schedule(
            timerKey = key,
            routingKey = routingKey,
            timerType = VirtualCluster.TIMER_TYPE,
            dueAt = dueAt,
            requiredCapability = VirtualCluster.CAPABILITY,
        )
    }

    private fun routingKeysOwnedBy(owner: String, over: String): Sequence<String> {
        val nodes = listOf(owner, over).map { ClusterNode(nodeId = it, capabilities = listOf(VirtualCluster.CAPABILITY), version = null, joinedAt = now(), lastSeenAt = now()) }
        return generateSequence(0) { it + 1 }.map { "monkey-rk-$it" }.filter { ownership.ownerFor(it, nodes)?.nodeId == owner }
    }

    private fun now(): OffsetDateTime = OffsetDateTime.now(testClock)

    // Infrastructure tables with no commands: each test starts from a cluster with no virtual
    // nodes and none of their tasks or timers, since the test clock resets between tests.
    private fun forgetVirtualNodes() {
        jdbi.useHandle<Exception> { handle ->
            handle.execute("DELETE FROM cluster_tasks_scheduled WHERE required_capability = ?", VirtualCluster.CAPABILITY)
            handle.execute("DELETE FROM cluster_timers WHERE required_capability = ?", VirtualCluster.CAPABILITY)
            handle.execute("DELETE FROM cluster_nodes WHERE jsonb_exists(capabilities, ?)", VirtualCluster.CAPABILITY)
        }
    }
}

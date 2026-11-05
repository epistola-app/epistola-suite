// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.cluster

import app.epistola.suite.cluster.schedules.ClusterScheduledTaskCatchUpPolicy
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskDefinition
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskExecutionScope
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskFailurePolicy
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskSchedule
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskScheduleCalculator
import app.epistola.suite.cluster.timers.ClusterTimerOwnership
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.OffsetDateTime
import kotlin.random.Random

/**
 * A seeded random walk over a three-node cluster: polls, runs held open while time moves, nodes
 * that pause and come back, clock jumps, operators triggering and disabling tasks, redeploys that
 * change a schedule, timers scheduled and cancelled, handlers that fail.
 *
 * After every step no key may be running on two nodes unless the first stopped renewing its lease.
 * At the end every timer ran no more often than it was scheduled, failed or abandoned; then the
 * cluster heals and every task and timer must run again.
 *
 * A failure names its seed. Rerun one walk with `CLUSTER_MONKEY_SEEDS=<seed>`, or a longer search
 * with `CLUSTER_MONKEY_SEEDS=1..200` and `CLUSTER_MONKEY_STEPS=600`.
 */
// Contends for installation-wide cluster rows from several node identities; needs its own database.
@TestPropertySource(properties = ["epistola.test.private-context=cluster-scheduling-monkey"])
class ClusterSchedulingMonkeyIT : IntegrationTestBase() {
    @Autowired private lateinit var jdbi: Jdbi

    @Autowired private lateinit var objectMapper: ObjectMapper

    @Autowired private lateinit var properties: ClusterProperties

    @Autowired private lateinit var calculator: ClusterScheduledTaskScheduleCalculator

    @Autowired private lateinit var ownership: ClusterTimerOwnership

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    fun `random walks keep every occurrence to one run at a time`(seed: Long) {
        testClock.reset()
        forgetVirtualNodes()
        VirtualCluster(jdbi, objectMapper, properties, calculator, ownership, mediator, testClock).use { cluster ->
            Walk(seed, cluster).run()
        }
    }

    private inner class Walk(private val seed: Long, private val cluster: VirtualCluster) {
        private val random = Random(seed)
        private val nodes = listOf("node-a", "node-b", "node-c").map { cluster.node(it) }
        private val paused = mutableSetOf<String>()
        private val log = mutableListOf<String>()
        private val timerSchedules = mutableMapOf<String, Int>()
        private val liveTimers = mutableSetOf<String>()
        private var holder: VirtualCluster.VirtualNode? = null
        private var deployVariant = 0

        fun run() {
            nodes.forEach { it.heartbeat() }
            nodes.forEach { it.register(*definitions(0)) }
            repeat(steps()) { step ->
                act(step)
                keepHolderAlive()
                check(cluster.overlaps.isEmpty()) { failure("overlap: ${cluster.overlaps}") }
            }
            settle()
            checkTimerRunCounts()
            heal()
        }

        private fun act(step: Int) {
            val node = nodes.random(random)
            val awake = node.nodeId !in paused
            when (random.nextInt(100)) {
                in 0..24 -> advance(Duration.ofSeconds(random.nextLong(1, 20)))
                in 25..29 -> advance(Duration.ofSeconds(random.nextLong(31, 120)))
                30 -> advance(Duration.ofMinutes(random.nextLong(10, 20)))
                in 31..44 -> if (awake && holder != node) record(step, "${node.nodeId} polls tasks") { node.pollTasks() }
                in 45..54 -> if (awake) record(step, "${node.nodeId} polls timers") { node.pollTimers() }
                in 55..60 -> if (awake && holder == null) holdPoll(step, node)
                in 61..64 -> if (holder != null) release(step)
                in 65..67 -> if (holder != null && holder!!.nodeId !in paused) pause(step, holder!!)
                in 68..70 -> if (paused.isNotEmpty()) resume(step)
                in 71..73 -> taskKeys().random(random).let { key -> record(step, "trigger $key") { node.tasks.triggerNow(key) } }
                in 74..75 -> taskKeys().random(random).let { key -> record(step, "disable $key") { node.tasks.disable(key) } }
                in 76..79 -> taskKeys().random(random).let { key -> record(step, "enable $key") { node.tasks.enable(key) } }
                in 80..81 -> if (awake) redeploy(step, node)
                in 82..90 -> scheduleTimer(step, node)
                in 91..92 -> liveTimers.randomOrNull(random)?.let { key -> record(step, "cancel $key") { node.timers.cancel(key) } }
                in 93..96 -> (taskKeys() + liveTimers).random(random).let { key -> record(step, "fail next $key") { cluster.failNext(key) } }
                else -> if (awake) record(step, "${node.nodeId} heartbeats") { node.heartbeat() }
            }
        }

        private fun holdPoll(step: Int, node: VirtualCluster.VirtualNode) {
            cluster.holdNextRun()
            val timers = random.nextBoolean()
            if (timers) node.pollTimersAsync() else node.pollTasksAsync()
            val key = cluster.awaitHeldOrIdle()
            if (key == null) {
                cluster.joinAll()
            } else {
                holder = node
            }
            log += "$step: ${node.nodeId} polls ${if (timers) "timers" else "tasks"} in the background, held on $key"
        }

        private fun release(step: Int) {
            log += "$step: release ${cluster.heldKey()} on ${holder?.nodeId}"
            cluster.releaseHeld()
            cluster.joinAll()
            holder = null
        }

        private fun pause(step: Int, node: VirtualCluster.VirtualNode) {
            log += "$step: ${node.nodeId} pauses mid-run of ${cluster.heldKey()}"
            cluster.abandonHeld()
            paused += node.nodeId
        }

        private fun resume(step: Int) {
            val nodeId = paused.random(random)
            paused -= nodeId
            log += "$step: $nodeId resumes"
            if (holder?.nodeId == nodeId) release(step)
        }

        private fun redeploy(step: Int, node: VirtualCluster.VirtualNode) {
            deployVariant = (deployVariant + 1) % 2
            record(step, "${node.nodeId} redeploys schedule variant $deployVariant") { node.register(*definitions(deployVariant)) }
        }

        private fun scheduleTimer(step: Int, node: VirtualCluster.VirtualNode) {
            val key = if (liveTimers.isNotEmpty() && random.nextInt(3) == 0) liveTimers.random(random) else "timer-${random.nextInt(12)}"
            val dueAt = now().plusSeconds(random.nextLong(0, 90))
            record(step, "schedule $key at +${Duration.between(now(), dueAt).seconds}s") {
                node.timers.schedule(key, "monkey:$key", VirtualCluster.TIMER_TYPE, dueAt, VirtualCluster.CAPABILITY)
            }
            timerSchedules.merge(key, 1, Int::plus)
            liveTimers += key
        }

        private fun advance(duration: Duration) {
            testClock.advanceBy(duration)
            log += "advance ${duration.seconds}s"
            nodes.filter { it.nodeId !in paused }.forEach { it.heartbeat() }
        }

        /** What an awake holder's heartbeat and renewer threads do while its handler runs. */
        private fun keepHolderAlive() {
            val node = holder ?: return
            if (node.nodeId in paused) return
            cluster.heldKey()?.let { node.renew(it) }
        }

        private fun settle() {
            if (holder != null) release(-1)
            paused.clear()
        }

        private fun checkTimerRunCounts() {
            val soft = SoftAssertions()
            timerSchedules.forEach { (key, scheduled) ->
                val allowed = scheduled + cluster.failures.count { it == key } + cluster.abandoned.count { it == key }
                soft.assertThat(cluster.runsOf(key).size)
                    .`as`(failure("timer $key ran more often than scheduled ($scheduled), failed or abandoned"))
                    .isLessThanOrEqualTo(allowed)
            }
            soft.assertAll()
        }

        /** Every node awake, every task enabled: all work must start running again. */
        private fun heal() {
            taskKeys().forEach { nodes.first().tasks.enable(it) }
            val since = now()
            repeat(HEAL_STEPS) {
                advance(Duration.ofSeconds(10))
                nodes.forEach { node ->
                    node.pollTasks()
                    node.pollTimers()
                }
            }
            val soft = SoftAssertions()
            val recent = now().minusMinutes(5)
            nodes.first().tasks.list().filter { it.taskKey in taskKeys() }.forEach { task ->
                if (task.executionScope == ClusterScheduledTaskExecutionScope.SINGLE_OWNER) {
                    soft.assertThat(task.lastCompletedAt).`as`(failure("${task.taskKey} last completed")).isAfter(recent)
                }
            }
            nodes.first().tasks.listNodeStates().filter { it.taskKey in taskKeys() }.forEach { state ->
                soft.assertThat(state.lastCompletedAt).`as`(failure("${state.taskKey} on ${state.nodeId} last completed")).isAfter(recent)
            }
            nodes.forEach { node ->
                soft.assertThat(cluster.runs.filter { it.nodeId == node.nodeId && it.key == EACH_NODE }.size)
                    .`as`(failure("$EACH_NODE runs on ${node.nodeId} while healing"))
                    .isGreaterThan(0)
            }
            soft.assertThat(nodes.first().timers.list(1_000).filter { it.requiredCapability == VirtualCluster.CAPABILITY })
                .`as`(failure("timers left after healing since $since"))
                .isEmpty()
            soft.assertAll()
        }

        private fun record(step: Int, what: String, action: () -> Any?) {
            log += "$step: $what"
            action()
        }

        private fun failure(what: String) = "seed $seed: $what\n" + log.takeLast(LOG_TAIL).joinToString("\n")
    }

    private fun definitions(variant: Int): Array<ClusterScheduledTaskDefinition> = arrayOf(
        task("rate", ClusterScheduledTaskSchedule.FixedRate(60_000L + variant * 15_000)),
        task("delay", ClusterScheduledTaskSchedule.FixedDelay(45_000)),
        task("cron", ClusterScheduledTaskSchedule.Cron(if (variant == 0) "0 * * * * *" else "30 * * * * *")),
        task("catch-up", ClusterScheduledTaskSchedule.FixedRate(90_000), catchUp = ClusterScheduledTaskCatchUpPolicy.CATCH_UP),
        task("advance", ClusterScheduledTaskSchedule.FixedRate(75_000), failure = ClusterScheduledTaskFailurePolicy.ADVANCE_ON_FAILURE),
        task(EACH_NODE, ClusterScheduledTaskSchedule.FixedDelay(60_000), scope = ClusterScheduledTaskExecutionScope.EACH_CAPABLE_NODE),
    )

    private fun task(
        key: String,
        schedule: ClusterScheduledTaskSchedule,
        scope: ClusterScheduledTaskExecutionScope = ClusterScheduledTaskExecutionScope.SINGLE_OWNER,
        catchUp: ClusterScheduledTaskCatchUpPolicy = ClusterScheduledTaskCatchUpPolicy.COALESCE,
        failure: ClusterScheduledTaskFailurePolicy = ClusterScheduledTaskFailurePolicy.RETRY_SAME_DUE,
    ) = ClusterScheduledTaskDefinition(
        taskKey = key,
        routingKey = "monkey:$key",
        taskType = VirtualCluster.TASK_TYPE,
        schedule = schedule,
        executionScope = scope,
        requiredCapability = VirtualCluster.CAPABILITY,
        failurePolicy = failure,
        catchUpPolicy = catchUp,
    )

    private fun taskKeys(): List<String> = definitions(0).map { it.taskKey }

    private fun now(): OffsetDateTime = OffsetDateTime.now(testClock)

    // Infrastructure tables with no commands: each walk starts from a cluster with no virtual nodes
    // and none of their tasks or timers, since the test clock resets between walks.
    private fun forgetVirtualNodes() {
        jdbi.useHandle<Exception> { handle ->
            handle.execute("DELETE FROM cluster_tasks_scheduled WHERE required_capability = ?", VirtualCluster.CAPABILITY)
            handle.execute("DELETE FROM cluster_timers WHERE required_capability = ?", VirtualCluster.CAPABILITY)
            handle.execute("DELETE FROM cluster_nodes WHERE jsonb_exists(capabilities, ?)", VirtualCluster.CAPABILITY)
        }
    }

    private fun steps(): Int = System.getenv("CLUSTER_MONKEY_STEPS")?.toInt() ?: 250

    companion object {
        private const val EACH_NODE = "each-node"
        private const val HEAL_STEPS = 120
        private const val LOG_TAIL = 40

        @JvmStatic
        fun seeds(): List<Long> {
            val spec = System.getenv("CLUSTER_MONKEY_SEEDS") ?: "1..8"
            return if (".." in spec) {
                val (from, to) = spec.split("..").map { it.trim().toLong() }
                (from..to).toList()
            } else {
                spec.split(",").map { it.trim().toLong() }
            }
        }
    }
}

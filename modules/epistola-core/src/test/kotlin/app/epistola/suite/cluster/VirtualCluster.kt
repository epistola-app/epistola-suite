// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.cluster

import app.epistola.suite.cluster.schedules.ClusterScheduledTask
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskDefinition
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskExecutionScope
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskHandler
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskRegistry
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskScheduleCalculator
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskScheduler
import app.epistola.suite.cluster.timers.ClusterTimer
import app.epistola.suite.cluster.timers.ClusterTimerHandler
import app.epistola.suite.cluster.timers.ClusterTimerOwnership
import app.epistola.suite.cluster.timers.ClusterTimerRegistry
import app.epistola.suite.cluster.timers.ClusterTimerResult
import app.epistola.suite.cluster.timers.ClusterTimerScheduler
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.observability.NodeIdentity
import app.epistola.suite.time.EpistolaClock
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.jdbi.v3.core.Jdbi
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Several cluster nodes inside one test JVM, sharing the test database.
 *
 * Each [VirtualNode] has its own [NodeIdentity] and its own registries and schedulers, built from
 * the production classes, so two of them contend for the same rows exactly as two pods would. They
 * advertise only [CAPABILITY], which the test's own nodes alone carry, so the Spring context's own
 * node and its built-in tasks never take part.
 *
 * Nothing runs on its own: a test decides when a node polls, heartbeats or renews its leases. A
 * poll can be started on a background thread ([VirtualNode.pollTasksAsync]) so a handler can hold a
 * run open while the test moves time and lets another node act — the interleavings a wall-clock
 * cluster produces by accident.
 */
class VirtualCluster(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
    baseProperties: ClusterProperties,
    private val calculator: ClusterScheduledTaskScheduleCalculator,
    private val ownership: ClusterTimerOwnership,
    private val mediator: Mediator,
    private val clock: Clock,
) : AutoCloseable {
    val properties: ClusterProperties = baseProperties.copy(capabilities = listOf(CAPABILITY))

    /** Every handler start, in order, across all nodes. */
    val runs: MutableList<Run> = CopyOnWriteArrayList()

    /** Runs that started while another node was still running the same key. */
    val overlaps: MutableList<String> = CopyOnWriteArrayList()

    private val running = ConcurrentHashMap<String, Pair<String, Instant>>()
    private val nextGate = AtomicReference<CountDownLatch?>()
    private val held = AtomicReference<Pair<String, CountDownLatch>?>()
    private var heldEntered = CountDownLatch(1)
    private val failing: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val maintenance = ClusterMaintenanceExecutor()
    private val threads = CopyOnWriteArrayList<Thread>()

    fun node(nodeId: String): VirtualNode = VirtualNode(nodeId)

    /** The next handler run, on any node and of any key, blocks until [releaseHeld]. */
    fun holdNextRun() {
        heldEntered = CountDownLatch(1)
        nextGate.set(CountDownLatch(1))
    }

    /** Waits until the held run has started and returns its key. */
    fun awaitHeld(): String {
        check(heldEntered.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "No handler run started" }
        return held.get()!!.first
    }

    fun releaseHeld() {
        held.getAndSet(null)?.second?.countDown()
    }

    /** The key of the run currently held, if any. */
    fun heldKey(): String? = held.get()?.first

    /**
     * Waits until either the armed hold caught a run (returns its key) or every background poll
     * finished without running anything (disarms the hold, returns null), so a later poll on the
     * test thread can never block on it.
     */
    fun awaitHeldOrIdle(): String? {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (System.nanoTime() < deadline) {
            if (heldEntered.count == 0L) return held.get()!!.first
            if (threads.none { it.isAlive }) {
                nextGate.set(null)
                // A run may have taken the gate between the two checks.
                return if (heldEntered.count == 0L) held.get()!!.first else null.also { threads.clear() }
            }
            Thread.onSpinWait()
        }
        error("A background poll neither ran a handler nor finished")
    }

    /**
     * Treats the held run as one whose node stopped renewing it — paused or partitioned. Another
     * node taking it over once its lease runs out is then expected, not an overlap.
     */
    fun abandonHeld() {
        held.get()?.let { (key, _) ->
            abandoned += key
            running.keys.removeIf { it == key || it.startsWith("$key@") }
        }
    }

    /** Keys of runs abandoned by [abandonHeld], once per abandoned run. */
    val abandoned: MutableList<String> = CopyOnWriteArrayList()

    /** Keys whose run failed on purpose, once per failure. */
    val failures: MutableList<String> = CopyOnWriteArrayList()

    fun failNext(key: String) {
        failing += key
    }

    fun runsOf(key: String): List<Run> = runs.filter { it.key == key }

    fun joinAll() {
        threads.forEach { it.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS)) }
        check(threads.none { it.isAlive }) { "A virtual node poll is still running" }
        threads.clear()
    }

    override fun close() {
        nextGate.getAndSet(null)
        releaseHeld()
        joinAll()
        maintenance.shutdown()
    }

    /** Runs that started on a second node because the first passed the hard run deadline. */
    val deadlineTakeovers: MutableList<String> = CopyOnWriteArrayList()

    /**
     * Runs one handler. [exclusiveKey] names what must not run twice at once: the key itself, or
     * key and node for a task every node runs.
     */
    private fun execute(nodeId: String, key: String, exclusiveKey: String = key) {
        val mine = nodeId to clock.instant()
        val previous = running.putIfAbsent(exclusiveKey, mine)
        if (previous != null) {
            val ranFor = Duration.between(previous.second, mine.second)
            if (ranFor.toMillis() > properties.scheduledTasks.maxRunDurationMs) {
                deadlineTakeovers += key
            } else {
                overlaps += "$key ran on $nodeId while still running on ${previous.first} (for $ranFor)"
            }
        }
        runs += Run(nodeId, key)
        try {
            nextGate.getAndSet(null)?.let { gate ->
                held.set(key to gate)
                heldEntered.countDown()
                check(gate.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "Held run of '$key' was never released" }
            }
            if (failing.remove(key)) {
                failures += key
                error("Induced failure of '$key'")
            }
        } finally {
            running.remove(exclusiveKey, mine)
        }
    }

    inner class VirtualNode(val nodeId: String) {
        val identity = NodeIdentity(nodeId)
        val nodes = ClusterNodeRegistry(jdbi, objectMapper, identity, properties, buildProperties = null, pdfRenderEnabled = false)
        val tasks = ClusterScheduledTaskRegistry(jdbi, objectMapper, identity, properties, calculator)
        val timers = ClusterTimerRegistry(jdbi, objectMapper, identity, properties)

        private val taskHandler = object : ClusterScheduledTaskHandler {
            override val taskType: String = TASK_TYPE
            override fun handle(task: ClusterScheduledTask) = execute(
                nodeId = nodeId,
                key = task.taskKey,
                exclusiveKey = when (task.executionScope) {
                    ClusterScheduledTaskExecutionScope.SINGLE_OWNER -> task.taskKey
                    ClusterScheduledTaskExecutionScope.EACH_CAPABLE_NODE -> "${task.taskKey}@$nodeId"
                },
            )
        }

        private val timerHandler = object : ClusterTimerHandler {
            override val timerType: String = TIMER_TYPE
            override fun handle(timer: ClusterTimer): ClusterTimerResult {
                execute(nodeId, timer.timerKey)
                return ClusterTimerResult.Complete
            }
        }

        val taskScheduler = ClusterScheduledTaskScheduler(
            nodeRegistry = nodes,
            taskRegistry = tasks,
            ownership = ownership,
            nodeIdentity = identity,
            properties = properties,
            scheduleCalculator = calculator,
            meterRegistry = SimpleMeterRegistry(),
            mediator = mediator,
            maintenanceExecutor = maintenance,
            handlers = listOf(taskHandler),
        )

        val timerScheduler = ClusterTimerScheduler(
            nodeRegistry = nodes,
            timerRegistry = timers,
            ownership = ownership,
            nodeIdentity = identity,
            properties = properties,
            meterRegistry = SimpleMeterRegistry(),
            mediator = mediator,
            maintenanceExecutor = maintenance,
            handlers = listOf(timerHandler),
        )

        fun register(vararg definitions: ClusterScheduledTaskDefinition) {
            tasks.registerAll(definitions.toList())
        }

        fun heartbeat(): ClusterNode = nodes.heartbeat()

        /** What the node's renewer does while [keys] are dispatching. */
        fun renew(vararg keys: String) {
            tasks.renewLeases(keys.toList())
            timers.renewLeases(keys.toList())
        }

        fun pollTasks(): Int = taskScheduler.poll()

        fun pollTimers(): Int = timerScheduler.poll()

        /** Starts a task poll on its own thread, under the test clock. */
        fun pollTasksAsync() = async { taskScheduler.poll() }

        /** Starts a timer poll on its own thread, under the test clock. */
        fun pollTimersAsync() = async { timerScheduler.poll() }

        private fun async(block: () -> Unit) {
            threads += thread(name = "virtual-$nodeId") { EpistolaClock.withClock(clock) { block() } }
        }
    }

    data class Run(val nodeId: String, val key: String)

    companion object {
        const val CAPABILITY = "monkey"
        const val TASK_TYPE = "monkey.task"
        const val TIMER_TYPE = "monkey.timer"
        private const val WAIT_SECONDS = 30L
    }
}

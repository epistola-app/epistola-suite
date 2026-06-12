package app.epistola.suite.testing.metrics

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide collector for test-run performance metrics.
 *
 * Populated only by cross-cutting hooks — never from individual tests:
 * - [TestTimingListener] (a JUnit Platform [org.junit.platform.launcher.TestExecutionListener]
 *   auto-registered via `META-INF/services`) records per-class wall time and writes
 *   the end-of-run report.
 * - [ContextBootCounter] (a Spring [org.springframework.context.ApplicationContextInitializer]
 *   registered via `META-INF/spring.factories`) counts fresh `ApplicationContext`
 *   boots — i.e. test-context cache misses, the cost driver behind context fragmentation.
 * - [MetricsRecordingMediator] (a `@Primary` [app.epistola.suite.mediator.Mediator]
 *   decorator) times every command/query dispatched through the mediator, so the
 *   per-operation cost profile (e.g. `CreateTenant`, which bootstraps the system
 *   catalog) is captured centrally — including nested dispatches — with no per-test code.
 *
 * This makes test performance evidence permanent and monitorable on every run
 * instead of something to eyeball locally. See `docs/testing.md`.
 */
object TestRunMetrics {
    /** Fresh ApplicationContext boots (test-context cache misses) in this JVM. */
    val contextBoots = AtomicLong(0)

    /** Boot time (millis) of each fresh ApplicationContext — initializer → context refreshed. */
    val contextBootMillis = CopyOnWriteArrayList<Long>()

    /** One-off Testcontainers Postgres container startup time (millis); 0 if never started. */
    val postgresStartupMillis = AtomicLong(0)

    /** Per-test-class wall time in millis (max across executions of the same class). */
    val classDurationsMs = ConcurrentHashMap<String, Long>()

    /** Command simple-name -> aggregated dispatch stats (count + total time). */
    val commandStats = ConcurrentHashMap<String, DispatchStat>()

    /** Query simple-name -> aggregated dispatch stats (count + total time). */
    val queryStats = ConcurrentHashMap<String, DispatchStat>()

    fun recordClassDuration(className: String, millis: Long) {
        classDurationsMs.merge(className, millis, ::maxOf)
    }

    fun recordContextBootNanos(nanos: Long) {
        contextBootMillis.add(nanos / 1_000_000)
    }

    fun recordPostgresStartupNanos(nanos: Long) {
        postgresStartupMillis.set(nanos / 1_000_000)
    }

    fun recordCommand(name: String, inclusiveNanos: Long, selfNanos: Long) {
        commandStats.computeIfAbsent(name) { DispatchStat() }.record(inclusiveNanos, selfNanos)
    }

    fun recordQuery(name: String, inclusiveNanos: Long, selfNanos: Long) {
        queryStats.computeIfAbsent(name) { DispatchStat() }.record(inclusiveNanos, selfNanos)
    }

    /** Tenants created during the run — derived from the `CreateTenant` command count. */
    fun tenantsCreated(): Long = commandStats["CreateTenant"]?.count?.get() ?: 0

    fun reset() {
        contextBoots.set(0)
        contextBootMillis.clear()
        postgresStartupMillis.set(0)
        classDurationsMs.clear()
        commandStats.clear()
        queryStats.clear()
    }

    /**
     * Thread-safe accumulator for one command/query type:
     * - [totalNanos] is **inclusive** time (the dispatch and everything it nested),
     * - [selfNanos] is **exclusive** time (this dispatch minus its nested dispatches)
     *   — the metric that says where work actually happens (a leaf DB write vs. an
     *   orchestrator that only delegates).
     */
    class DispatchStat {
        val count = AtomicLong(0)
        val totalNanos = AtomicLong(0)
        val selfNanos = AtomicLong(0)

        fun record(inclusiveNanos: Long, selfNanos: Long) {
            count.incrementAndGet()
            totalNanos.addAndGet(inclusiveNanos)
            this.selfNanos.addAndGet(selfNanos)
        }

        fun totalMillis(): Long = totalNanos.get() / 1_000_000

        fun selfMillis(): Long = selfNanos.get() / 1_000_000
    }
}

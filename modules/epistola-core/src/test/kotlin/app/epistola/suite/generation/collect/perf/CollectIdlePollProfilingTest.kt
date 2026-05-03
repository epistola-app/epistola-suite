package app.epistola.suite.generation.collect.perf

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.generation.collect.commands.TouchConsumerNode
import app.epistola.suite.generation.collect.queries.FetchGenerationResults
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import io.micrometer.core.instrument.MeterRegistry
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.statement.SqlLogger
import org.jdbi.v3.core.statement.StatementContext
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Profiles where the ~5 ms per empty poll actually goes. Hooks JDBI's
 * `SqlLogger` to capture per-statement wall-clock (including round-trip),
 * brackets each handler dispatch with `System.nanoTime()` to capture
 * end-to-end Touch + Fetch wall-clock per poll, and reads Hikari's
 * `connections.acquire` timer for the pool-acquisition baseline.
 *
 * The output is a single console table at the end of the run:
 *
 *   ```
 *   Per-statement (sums across all polls):
 *     touch SELECT peers   N calls,  M ms total,  μs avg/call
 *     touch UPSERT self    ...
 *     fetch SELECT cursors ...
 *     fetch SELECT rows    ...
 *
 *   Per-poll handler wall-clock:
 *     Touch handler        avg μs
 *     Fetch handler        avg μs
 *
 *   Hikari connection acquire (μs): mean / max / count
 *
 *   Per-poll headline:
 *     wall-clock                 μs
 *     accounted by SQL (sum)     μs
 *     accounted by handlers      μs
 *     unaccounted overhead       μs   (= mediator dispatch + transaction begin/commit
 *                                       + JDBI parameter binding + connection
 *                                         acquire + everything else)
 *   ```
 *
 * Single-consumer to keep pool-contention noise out of the breakdown.
 */
@Tag("perf")
@TestPropertySource(
    properties = [
        "epistola.generation.polling.enabled=false",
        "spring.datasource.hikari.maximum-pool-size=64",
        "spring.datasource.hikari.minimum-idle=8",
        // Make sure Hikari publishes its metrics so we can read the acquire timer.
        "spring.datasource.hikari.register-mbeans=true",
    ],
)
class CollectIdlePollProfilingTest : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    @Autowired(required = false)
    private var meterRegistry: MeterRegistry? = null

    private val durationSeconds = 10

    @Test
    fun `profile single-consumer empty-poll cost breakdown`() {
        val tenantKey = TenantKey.of("perf-${UUID.randomUUID().toString().take(8)}")
        val nodeId = "profile-node-1"
        val consumerId = "profile-consumer"

        // Stabilize the ring with two touches so the rest of the test only
        // exercises the steady-state (alone-on-the-ring) path.
        withMediator {
            TouchConsumerNode(tenantKey, consumerId, nodeId).execute()
            TouchConsumerNode(tenantKey, consumerId, nodeId).execute()
        }

        // Per-template SQL stats. Key = first 60 chars of normalized SQL so the
        // four queries (Touch peers SELECT, Touch UPSERT, Fetch cursors SELECT,
        // Fetch rows SELECT) bucket naturally.
        val sqlNanos = ConcurrentHashMap<String, AtomicLong>()
        val sqlCount = ConcurrentHashMap<String, AtomicLong>()

        jdbi.setSqlLogger(object : SqlLogger {
            override fun logBeforeExecution(context: StatementContext) {
                context.define("perf.start", System.nanoTime())
            }

            override fun logAfterExecution(context: StatementContext) {
                val start = context.getAttribute("perf.start") as? Long ?: return
                val durationNs = System.nanoTime() - start
                val key = normalizeSql(context.rawSql ?: "?")
                sqlNanos.computeIfAbsent(key) { AtomicLong() }.addAndGet(durationNs)
                sqlCount.computeIfAbsent(key) { AtomicLong() }.incrementAndGet()
            }

            override fun logException(context: StatementContext, ex: SQLException) = Unit
        })

        // Per-handler wall-clock (Touch + Fetch as observed at the mediator
        // dispatch boundary — includes everything inside but ALSO the dispatch
        // overhead, which is exactly the slice we want to compare against the
        // raw SQL totals).
        val touchTotalNs = AtomicLong(0)
        val fetchTotalNs = AtomicLong(0)
        val pollCount = AtomicLong(0)
        val pollWallClockTotalNs = AtomicLong(0)

        // Read Hikari's acquire timer count BEFORE the test so we can subtract
        // the baseline at the end and report only what this test caused.
        val acquireTimerName = "hikaricp.connections.acquire"
        val acquireBefore = meterRegistry?.find(acquireTimerName)?.timer()
        val acquireCountBefore = acquireBefore?.count() ?: 0L
        val acquireTotalNanosBefore = acquireBefore?.totalTime(TimeUnit.NANOSECONDS) ?: 0.0

        // Tight-loop polling for `durationSeconds`. One thread, one consumer —
        // keeps pool contention out of the picture.
        val running = AtomicBoolean(true)
        val pollerThread = Thread.ofVirtual()
            .name("perf-profile-$nodeId")
            .start {
                withMediator {
                    while (running.get()) {
                        val pollStart = System.nanoTime()

                        val touchStart = System.nanoTime()
                        val assignment = TouchConsumerNode(tenantKey, consumerId, nodeId).execute()
                        touchTotalNs.addAndGet(System.nanoTime() - touchStart)

                        val fetchStart = System.nanoTime()
                        FetchGenerationResults(
                            tenantId = tenantKey,
                            consumerId = consumerId,
                            partitions = assignment.mine.toSet(),
                            limit = 100,
                        ).query()
                        fetchTotalNs.addAndGet(System.nanoTime() - fetchStart)

                        pollWallClockTotalNs.addAndGet(System.nanoTime() - pollStart)
                        pollCount.incrementAndGet()
                    }
                }
            }

        Thread.sleep(durationSeconds * 1000L)
        running.set(false)
        pollerThread.join(10_000)

        val polls = pollCount.get()
        val pollWallClockUs = (pollWallClockTotalNs.get() / 1_000) / polls
        val touchUs = (touchTotalNs.get() / 1_000) / polls
        val fetchUs = (fetchTotalNs.get() / 1_000) / polls

        val acquireAfter = meterRegistry?.find(acquireTimerName)?.timer()
        val acquireCountDelta = (acquireAfter?.count() ?: 0L) - acquireCountBefore
        val acquireNanosDelta = (acquireAfter?.totalTime(TimeUnit.NANOSECONDS) ?: 0.0) - acquireTotalNanosBefore
        val acquireMeanUs = if (acquireCountDelta == 0L) 0.0 else (acquireNanosDelta / acquireCountDelta) / 1_000.0

        // Per-statement table
        val sqlLines = sqlNanos.keys.sorted().map { key ->
            val totalNs = sqlNanos[key]!!.get()
            val count = sqlCount[key]!!.get()
            val avgUs = if (count == 0L) 0.0 else (totalNs.toDouble() / count) / 1_000.0
            val totalMs = totalNs / 1_000_000
            "  $key" to "$count calls, $totalMs ms total, ${"%.1f".format(avgUs)} μs/call"
        }
        val sqlTotalNs = sqlNanos.values.sumOf { it.get() }
        val sqlAccountedUs = (sqlTotalNs / 1_000) / polls
        val handlerAccountedUs = touchUs + fetchUs
        val unaccountedUs = pollWallClockUs - handlerAccountedUs

        PerfReport.consoleBlock(
            title = "Empty-poll cost breakdown — 1 consumer, ${durationSeconds}s",
            lines = listOf(
                "polls" to "$polls",
                "wall-clock per poll (μs)" to "$pollWallClockUs",
                "" to "",
                "--- per-handler dispatch (μs/poll) ---" to "",
                "  Touch handler" to "$touchUs",
                "  Fetch handler" to "$fetchUs",
                "  handlers total" to "$handlerAccountedUs",
                "  unaccounted (mediator + Spring + scoped value etc.)" to "$unaccountedUs",
                "" to " ",
                "--- per-statement (avg over $polls polls) ---" to "",
            ) + sqlLines + listOf(
                "  SQL accounted total" to "$sqlAccountedUs μs/poll",
                "  SQL as % of poll wall-clock" to "${"%.1f".format(100.0 * sqlAccountedUs / pollWallClockUs)}%",
                "" to "  ",
                "--- Hikari pool acquire timer ---" to "",
                "  acquire count delta" to "$acquireCountDelta",
                "  mean acquire (μs)" to "%.1f".format(acquireMeanUs),
                "  acquires per poll (sanity)" to "%.2f".format(acquireCountDelta.toDouble() / polls),
            ),
        )
    }

    /**
     * Collapse whitespace + truncate to 60 chars so the four distinct query
     * templates bucket cleanly without us hard-coding the SQL strings here.
     */
    private fun normalizeSql(rawSql: String): String {
        val collapsed = rawSql.trim().replace(Regex("\\s+"), " ")
        return if (collapsed.length > 60) "${collapsed.take(60)}…" else collapsed
    }
}

private typealias SQLException = java.sql.SQLException

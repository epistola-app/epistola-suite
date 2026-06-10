package app.epistola.suite.generation.collect.perf

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.generation.collect.domain.Partition
import app.epistola.suite.generation.collect.domain.ResultStatus
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.SimulatedConsumer
import app.epistola.suite.testing.SimulatedConsumerFactory
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import java.io.File
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Consumer-side throughput perf test for the v0.3 collect mechanism.
 *
 * Question this answers: **how many messages can N consumer nodes drain
 * from `generation_results` per second?**
 *
 * Producer is intentionally absent: rows are bulk-inserted directly via
 * JDBI (no `EmitGenerationResult`, no executor, no `JobPoller`). That
 * isolates the collect path — `TouchConsumerNode` ring assignment +
 * `AcknowledgeGenerationResults` cursor advance + `FetchGenerationResults`
 * read throughput + Postgres-pool / cursor-contention behaviour.
 *
 * Producer-side perf and the realistic end-to-end (executor + JobPoller +
 * collect) are separate follow-on tests — see `docs/collect-performance.md`.
 *
 * Invocation:
 * ```
 * ./gradlew :modules:epistola-core:perfTest \
 *   --tests app.epistola.suite.generation.collect.perf.CollectConsumerThroughputPerfTest
 * ```
 *
 * Append a hardware tag for the CSV with `-Dperf.hardware=mac-m4-pro`
 * (or similar) so reports from different machines are distinguishable.
 */
@Tag("perf")
@TestPropertySource(
    properties = [
        // Disable JobPoller — we don't want background work mucking with our
        // measurements. We're measuring drain-only.
        "epistola.generation.polling.enabled=false",
        // Default test pool is 10. With 16 parallel consumers we'd be pool-bound
        // before we hit Postgres limits. Bump it so the pool isn't the bottleneck.
        "spring.datasource.hikari.maximum-pool-size=64",
        "spring.datasource.hikari.minimum-idle=8",
    ],
)
class CollectConsumerThroughputPerfTest : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    @Autowired
    private lateinit var consumerFactory: SimulatedConsumerFactory

    @ParameterizedTest(name = "{0} consumers draining {1} rows")
    @MethodSource("matrix")
    fun consumerThroughput(numConsumers: Int, totalRows: Int) {
        val tenantKey = TenantKey.of("perf-${UUID.randomUUID().toString().take(8)}")

        // One consumer-id, N nodes — same consumer cluster, different nodes,
        // partitions split by the ring.
        val consumers = (1..numConsumers).map { i ->
            consumerFactory.consumer(tenantKey, "perf-consumer", "node-$i")
        }
        // Touch all once to register on the ring, then touch all again so each
        // sees the post-stabilize assignment (the first node alone gets ALL 64;
        // subsequent touches narrow it down). withMediator binds the test
        // principal + mediator into the current thread's ScopedValue so the
        // SimulatedConsumer can dispatch TouchConsumerNode through the mediator.
        withMediator {
            consumers.forEach { it.poll() }
            consumers.forEach { it.poll() }
        }
        bulkSeed(tenantKey, totalRows)

        // Drain phase — start of measurement
        val receivedSequences = ConcurrentHashMap.newKeySet<Long>()
        val received = AtomicLong(0)
        val perConsumerCount = ConcurrentHashMap<String, AtomicLong>()
        val perConsumerNonEmptyPolls = ConcurrentHashMap<String, AtomicLong>()
        val perConsumerTotalPolls = ConcurrentHashMap<String, AtomicLong>()
        consumers.forEach {
            perConsumerCount[it.nodeId] = AtomicLong(0)
            perConsumerNonEmptyPolls[it.nodeId] = AtomicLong(0)
            perConsumerTotalPolls[it.nodeId] = AtomicLong(0)
        }

        val startNanos = System.nanoTime()
        val pollerThreads = consumers.map { c ->
            drainThread(
                consumer = c,
                target = totalRows.toLong(),
                receivedSequences = receivedSequences,
                received = received,
                perConsumerCount = perConsumerCount,
                perConsumerNonEmptyPolls = perConsumerNonEmptyPolls,
                perConsumerTotalPolls = perConsumerTotalPolls,
            )
        }
        // Cap each drainer at 5 minutes — anything longer is a bug, not a perf
        // measurement worth waiting for.
        pollerThreads.forEach { it.join(5 * 60 * 1000L) }
        val durationMs = (System.nanoTime() - startNanos) / 1_000_000

        val totalReceived = received.get()
        val perConsumerCounts = consumers.map { perConsumerCount[it.nodeId]!!.get() }
        val totalPolls = consumers.sumOf { perConsumerTotalPolls[it.nodeId]!!.get() }
        val nonEmptyPolls = consumers.sumOf { perConsumerNonEmptyPolls[it.nodeId]!!.get() }

        val throughput = totalReceived.toDouble() / (durationMs / 1000.0)
        val pollingEfficiency = if (totalPolls == 0L) 0.0 else nonEmptyPolls.toDouble() / totalPolls.toDouble()

        // Reporting
        PerfReport.consoleBlock(
            title = "Collect consumer throughput — $numConsumers consumers, $totalRows rows",
            lines = listOf(
                "totalReceived" to "$totalReceived",
                "durationMs" to "$durationMs",
                "throughputMsgPerSec" to "%.1f".format(throughput),
                "perConsumerCounts" to perConsumerCounts.toString(),
                "perConsumerStddev" to "%.1f".format(PerfReport.stddev(perConsumerCounts)),
                "perConsumerMin" to "${perConsumerCounts.min()}",
                "perConsumerMax" to "${perConsumerCounts.max()}",
                "totalPolls" to "$totalPolls",
                "nonEmptyPolls" to "$nonEmptyPolls",
                "pollingEfficiency" to "%.3f".format(pollingEfficiency),
            ),
        )

        PerfReport.appendCsv(
            file = File("build/perf-reports/collect-consumer-throughput.csv"),
            row = mapOf(
                "timestamp" to PerfReport.nowIso(),
                "test" to "CollectConsumerThroughputPerfTest",
                "params" to "n=$numConsumers,rows=$totalRows",
                "totalRows" to totalRows,
                "numConsumers" to numConsumers,
                "durationMs" to durationMs,
                "throughputMsgPerSec" to "%.1f".format(throughput),
                "perConsumerMin" to perConsumerCounts.min(),
                "perConsumerMax" to perConsumerCounts.max(),
                "perConsumerStddev" to "%.1f".format(PerfReport.stddev(perConsumerCounts)),
                "pollingEfficiency" to "%.3f".format(pollingEfficiency),
                "hardwareTag" to PerfReport.hardwareTag(),
                "jvm" to PerfReport.jvmTag(),
            ),
        )

        // Sanity: every row must have been received by exactly one consumer
        // (no per-row dedup is needed at this scale — ack semantics deduplicate
        // for us). If we lose rows, the throughput number is meaningless.
        assertThat(totalReceived)
            .`as`("all $totalRows pre-seeded rows must be drained")
            .isEqualTo(totalRows.toLong())
    }

    /**
     * Bulk-insert `totalRows` rows directly into `generation_results` using
     * JDBI's `prepareBatch`. Routing keys are `perf-0`..`perf-N-1`; partitions
     * computed via `Partition.partitionFor` so distribution matches the
     * production hash. Status COMPLETED, document/template/etc. left null —
     * the collect path doesn't dereference those for delivery.
     *
     * Done in chunks of 1000 to keep parameter binds inside Postgres limits.
     */
    private fun bulkSeed(tenantKey: TenantKey, totalRows: Int) {
        val now = OffsetDateTime.now()
        val chunkSize = 1000
        var offset = 0
        while (offset < totalRows) {
            val end = (offset + chunkSize).coerceAtMost(totalRows)
            jdbi.useHandle<Exception> { handle ->
                val batch = handle.prepareBatch(
                    """
                    INSERT INTO generation_results (
                        partition, request_id, tenant_key, routing_key, status, completed_at
                    )
                    VALUES (
                        :partition, :requestId, :tenantKey, :routingKey, :status, :completedAt
                    )
                    """,
                )
                for (i in offset until end) {
                    val routingKey = "perf-$i"
                    batch.bind("partition", Partition.partitionFor(routingKey))
                        .bind("requestId", UUID.randomUUID())
                        .bind("tenantKey", tenantKey)
                        .bind("routingKey", routingKey)
                        .bind("status", ResultStatus.COMPLETED.name)
                        .bind("completedAt", now)
                        .add()
                }
                batch.execute()
            }
            offset = end
        }
    }

    private fun drainThread(
        consumer: SimulatedConsumer,
        target: Long,
        receivedSequences: MutableSet<Long>,
        received: AtomicLong,
        perConsumerCount: ConcurrentHashMap<String, AtomicLong>,
        perConsumerNonEmptyPolls: ConcurrentHashMap<String, AtomicLong>,
        perConsumerTotalPolls: ConcurrentHashMap<String, AtomicLong>,
    ): Thread = Thread.ofVirtual()
        .name("perf-drain-${consumer.nodeId}")
        .start {
            // Mediator + SecurityContext are ScopedValue-bound on the calling
            // thread; virtual threads started here don't inherit them. Bind
            // them ourselves for the duration of this drain.
            withMediator {
                var lastSeq: Long? = null
                // Tolerate a few empty polls in a row before exiting (ring may
                // briefly be in an in-between state if another consumer is
                // also winding down). Bail when target reached.
                var consecutiveEmpty = 0
                while (received.get() < target && consecutiveEmpty < 5) {
                    perConsumerTotalPolls[consumer.nodeId]!!.incrementAndGet()
                    val page = consumer.poll(acknowledgeUpTo = lastSeq, limit = 1000)
                    if (page.rows.isEmpty()) {
                        consecutiveEmpty++
                        Thread.sleep(10)
                    } else {
                        consecutiveEmpty = 0
                        perConsumerNonEmptyPolls[consumer.nodeId]!!.incrementAndGet()
                        val uniqueRows = page.rows.count { receivedSequences.add(it.sequence) }.toLong()
                        if (uniqueRows > 0) {
                            received.addAndGet(uniqueRows)
                            perConsumerCount[consumer.nodeId]!!.addAndGet(uniqueRows)
                        }
                        lastSeq = page.lastSequence
                    }
                }
                // Final ack so the cursor reflects what we drained — keeps
                // the DB tidy in case the test class is reused.
                if (lastSeq != null) {
                    consumer.acknowledge(lastSeq)
                }
            }
        }

    companion object {
        @JvmStatic
        fun matrix(): List<Arguments> = listOf(
            // Small row counts (10k) drain in ~200 ms at high consumer counts;
            // the result reflects single-batch latency more than steady-state
            // throughput. Larger counts (100k) get closer to a sustained number.
            Arguments.of(1, 10_000),
            Arguments.of(4, 10_000),
            Arguments.of(16, 10_000),
            Arguments.of(1, 100_000),
            Arguments.of(4, 100_000),
            Arguments.of(16, 100_000),
        )
    }
}

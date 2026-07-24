// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.generation.collect.perf

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.SimulatedConsumer
import app.epistola.suite.testing.SimulatedConsumerFactory
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Idle-poll cost for the v0.3 collect mechanism.
 *
 * Question this answers: **what does an empty poll cost the suite?**
 * In other words: when N consumer nodes are polling but no rows are
 * ever emitted, how many polls/sec can we sustain and what's the
 * per-poll latency distribution?
 *
 * Why we want this number: it tells us whether a server-side cache
 * (in-memory or `last_emit` sentinel table) would meaningfully cut
 * the cost of empty polls. If empty polls already cost ≤ 1 ms p99
 * the cache isn't worth the complexity. If they cost > 5 ms p99 we
 * should pursue it. Decision rule lives in the plan that
 * commissioned this test.
 *
 * Each poll dispatches Touch + Ack + Fetch through the mediator
 * (same path as the real `/api/tenants/{tid}/generation/collect`
 * endpoint). The Ack is a no-op until `lastSequence` is non-null,
 * which never happens because the system stays empty — so this
 * measures pure Touch + Fetch cost.
 *
 * Producer is intentionally absent. This is the symmetric idle-side
 * companion to `CollectConsumerThroughputPerfTest`'s drain-side
 * measurement.
 *
 * Invocation:
 * ```
 * ./gradlew :modules:epistola-core:perfTest \
 *   --tests app.epistola.suite.generation.collect.perf.CollectIdlePollPerfTest \
 *   -Dperf.hardware=<short-machine-tag>
 * ```
 */
@Tag("perf")
@TestPropertySource(
    properties = [
        // Disable JobPoller so background work doesn't muck with the measurement.
        "epistola.generation.polling.enabled=false",
        // Same pool tuning as the throughput test; default 10 would bottleneck
        // before Postgres did at 64+ consumers.
        "spring.datasource.hikari.maximum-pool-size=64",
        "spring.datasource.hikari.minimum-idle=8",
    ],
)
class CollectIdlePollPerfTest : IntegrationTestBase() {

    @Autowired
    private lateinit var consumerFactory: SimulatedConsumerFactory

    @ParameterizedTest(name = "{0} idle consumers polling for {1}s")
    @MethodSource("matrix")
    fun idlePollLatency(numConsumers: Int, durationSeconds: Int) {
        val tenantKey = TenantKey.of("perf-${UUID.randomUUID().toString().take(8)}")
        // No bulk seed. Empty system on purpose.

        val consumers = (1..numConsumers).map { i ->
            consumerFactory.consumer(tenantKey, "perf-consumer", "node-$i")
        }
        // Stabilize the ring — first touch alone gets ALL 64 partitions; second
        // touch (after others have joined) narrows to the per-node share.
        withMediator {
            consumers.forEach { it.poll() }
            consumers.forEach { it.poll() }
        }

        val perPollLatenciesNs = ConcurrentLinkedQueue<Long>()
        val totalPolls = AtomicLong(0)
        val running = AtomicBoolean(true)

        val pollerThreads = consumers.map { c -> tightPollLoop(c, running, totalPolls, perPollLatenciesNs) }

        // Run the polling phase for the configured duration, then signal stop.
        Thread.sleep(durationSeconds * 1000L)
        running.set(false)
        pollerThreads.forEach { it.join(10_000) }

        val polls = totalPolls.get()
        val pollsPerSec = polls.toDouble() / durationSeconds
        val pollsPerSecPerConsumer = pollsPerSec / numConsumers
        val latenciesMs = perPollLatenciesNs.toList().map { it / 1_000_000 }
        val p50 = PerfReport.percentile(latenciesMs, 50.0)
        val p95 = PerfReport.percentile(latenciesMs, 95.0)
        val p99 = PerfReport.percentile(latenciesMs, 99.0)
        val p999 = PerfReport.percentile(latenciesMs, 99.9)

        PerfReport.consoleBlock(
            title = "Idle polling — $numConsumers consumers, ${durationSeconds}s",
            lines = listOf(
                "totalPolls" to "$polls",
                "pollsPerSec (aggregate)" to "%.1f".format(pollsPerSec),
                "pollsPerSec (per consumer)" to "%.2f".format(pollsPerSecPerConsumer),
                "latency p50 (ms)" to "$p50",
                "latency p95 (ms)" to "$p95",
                "latency p99 (ms)" to "$p99",
                "latency p99.9 (ms)" to "$p999",
            ),
        )

        PerfReport.appendCsv(
            file = File("build/perf-reports/collect-idle-poll.csv"),
            row = mapOf(
                "timestamp" to PerfReport.nowIso(),
                "test" to "CollectIdlePollPerfTest",
                "params" to "n=$numConsumers,duration=${durationSeconds}s",
                "totalRows" to 0,
                "numConsumers" to numConsumers,
                "durationMs" to (durationSeconds * 1000L),
                "throughputMsgPerSec" to "%.1f".format(pollsPerSec),
                "perConsumerMin" to "$p50",
                "perConsumerMax" to "$p99",
                "perConsumerStddev" to "%.2f".format(pollsPerSecPerConsumer),
                "pollingEfficiency" to "0.0", // by construction — empty system
                "hardwareTag" to PerfReport.hardwareTag(),
                "jvm" to PerfReport.jvmTag(),
            ),
        )
    }

    /**
     * Tight poll loop on a virtual thread. No inter-poll sleep — we want the
     * worst-case suite load. `running` is volatile-set to false from the test
     * thread to stop us. `withMediator` rebinds the SecurityContext +
     * MediatorContext into this virtual thread (ScopedValue doesn't propagate
     * across `Thread.ofVirtual().start { }`).
     */
    private fun tightPollLoop(
        consumer: SimulatedConsumer,
        running: AtomicBoolean,
        totalPolls: AtomicLong,
        perPollLatenciesNs: ConcurrentLinkedQueue<Long>,
    ): Thread = Thread.ofVirtual()
        .name("perf-idlepoll-${consumer.nodeId}")
        .start {
            withMediator {
                while (running.get()) {
                    val start = System.nanoTime()
                    consumer.poll(acknowledgeUpTo = null, limit = 100)
                    val latencyNs = System.nanoTime() - start
                    perPollLatenciesNs.add(latencyNs)
                    totalPolls.incrementAndGet()
                }
            }
        }

    companion object {
        @JvmStatic
        fun matrix(): List<Arguments> = listOf(
            // 30s gives enough samples (>5k polls per consumer at typical
            // rates) for percentiles to be stable while keeping the whole
            // matrix under ~3 minutes total runtime.
            Arguments.of(1, 30),
            Arguments.of(16, 30),
            Arguments.of(64, 30),
            Arguments.of(256, 30),
        )
    }
}

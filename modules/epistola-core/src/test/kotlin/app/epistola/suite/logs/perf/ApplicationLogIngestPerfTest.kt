// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.logs.perf

import app.epistola.suite.common.UUIDv7
import app.epistola.suite.config.JdbiConfig
import app.epistola.suite.generation.collect.perf.PerfReport
import app.epistola.suite.logs.ApplicationLogIngestor
import app.epistola.suite.logs.ApplicationLogProperties
import app.epistola.suite.logs.ApplicationLogRecord
import app.epistola.suite.observability.NodeIdentity
import app.epistola.suite.testing.TestcontainersConfiguration
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.statement.SqlLogger
import org.jdbi.v3.core.statement.StatementContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Profile
import org.springframework.test.context.ActiveProfiles
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.io.File
import java.sql.SQLException
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

/**
 * Load/perf test for application-log capture: **how many log messages can we
 * persist per second via batched inserts while keeping the database unstrained?**
 *
 * It drives the real batched-insert path ([ApplicationLogIngestor.enqueue] →
 * `flush()` → JDBI `prepareBatch`) with one drain thread (mirrors the single
 * production worker) fed by virtual-thread producers, and measures throughput,
 * effective batch size, and per-batch `INSERT` latency (the DB-strain signal).
 *
 * Isolation: it boots a **purpose-built slim context** ([LogIngestPerfContext])
 * — DataSource + Flyway + JDBI only — instead of the full `TestApplication`, so
 * no cluster schedulers / JobPoller / MCP / generation beans contend for the DB
 * or CPU while we measure. The shared Testcontainers Postgres is tmpfs-backed, so
 * disk is RAM-speed: numbers are optimistic vs. real disk but consistent for
 * batch-size/throughput comparison (recorded in the report).
 *
 * Opt-in — run with:
 * ```
 * ./gradlew :modules:epistola-core:perfTest \
 *   --tests "*ApplicationLogIngestPerfTest" -Dperf.hardware=mac-m4-pro
 * ```
 * Results: console blocks + `build/perf-reports/application-log-ingest.csv`.
 */
// Both tags, mirroring the IntegrationTestBase-derived perf tests: `integration`
// keeps it out of `unitTest` (and `integrationTest` excludes `perf`), so this
// Docker-backed test runs ONLY under the opt-in `perfTest` task.
@SpringBootTest(classes = [LogIngestPerfContext::class], webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("log-ingest-perf")
@Tag("perf")
@Tag("integration")
class ApplicationLogIngestPerfTest {

    @Autowired
    private lateinit var jdbi: Jdbi

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    /** Per-`INSERT ... application_log` wall-clock durations (nanos), captured via the SqlLogger. */
    private val insertNanos = ConcurrentLinkedQueue<Long>()

    private val noopSqlLogger = object : SqlLogger {
        override fun logBeforeExecution(context: StatementContext) = Unit
        override fun logAfterExecution(context: StatementContext) = Unit
        override fun logException(context: StatementContext, ex: SQLException) = Unit
    }

    @AfterEach
    fun resetSqlLogger() {
        jdbi.setSqlLogger(noopSqlLogger)
    }

    /**
     * Sustained throughput as a function of batch size: a fixed total with a queue
     * big enough that nothing drops, so we isolate the single batched writer's DB
     * write rate and the throughput/latency trade-off of larger batches.
     */
    @ParameterizedTest(name = "batchSize={0}")
    @ValueSource(ints = [50, 200, 500, 1000])
    fun sustainedThroughput(batchSize: Int) {
        val total = 200_000
        val result = runWorkload(
            scenario = "sustained",
            queueCapacity = total, // no drops — measure pure persist throughput
            batchSize = batchSize,
            producers = 4,
            recordsPerProducer = total / 4,
        )

        assertThat(result.dropped).`as`("queue sized to total → no drops").isEqualTo(0L)
        assertThat(result.persistFailures).`as`("DB must never error").isEqualTo(0L)
        assertThat(result.persisted).`as`("every record persisted").isEqualTo(total.toLong())
    }

    /**
     * Overload / back-pressure protection: feed far faster than one writer can
     * drain through a realistically bounded queue. The DB writes at its steady
     * batched rate, excess is shed via drops (memory stays capped), and the DB is
     * never the thing that breaks — `persistFailures == 0`, every record accounted.
     */
    @Test
    fun overloadShedsLoadWithoutStrainingTheDatabase() {
        val total = 1_000_000
        val result = runWorkload(
            scenario = "overload",
            queueCapacity = 10_000, // realistic bound — excess is dropped, not buffered
            batchSize = 200,
            producers = 8,
            recordsPerProducer = total / 8,
        )

        assertThat(result.persistFailures).`as`("DB must never error under overload").isEqualTo(0L)
        assertThat(result.persisted + result.dropped)
            .`as`("every record is either persisted or deliberately dropped")
            .isEqualTo(total.toLong())
        assertThat(result.dropped).`as`("overload should actually exercise the drop path").isGreaterThan(0L)
    }

    // -- workload runner -----------------------------------------------------

    private fun runWorkload(
        scenario: String,
        queueCapacity: Int,
        batchSize: Int,
        producers: Int,
        recordsPerProducer: Int,
    ): Result {
        truncate()
        insertNanos.clear()
        installInsertLatencyLogger()

        val total = (recordsPerProducer.toLong()) * producers
        val registry = SimpleMeterRegistry()
        val ingestor = ApplicationLogIngestor(
            jdbi = jdbi,
            nodeIdentity = NodeIdentity("perf-node"),
            objectMapper = objectMapper,
            meterRegistry = registry,
            // maxRatePerSecond = 0 → no rate cap; this test measures raw DB write capacity.
            properties = ApplicationLogProperties(queueCapacity = queueCapacity, batchSize = batchSize, maxRatePerSecond = 0),
        )

        fun counter(name: String): Long = registry.find(name).counter()?.count()?.toLong() ?: 0L

        // Single drain thread = the production worker. Started first so it drains
        // concurrently with producers (steady state, not a drain-after-fill burst).
        val drainStop = AtomicLong(0) // 0 = running, 1 = stop
        var persistedLocal = 0L
        val drain = Thread.ofVirtual().name("perf-log-drain").start {
            while (drainStop.get() == 0L) {
                val n = ingestor.flush()
                persistedLocal += n
                if (persistedLocal + counter("epistola.logs.dropped") >= total) break
                if (n == 0) LockSupport.parkNanos(50_000) // 50µs — queue transiently empty
            }
            // Final sweep for anything enqueued after the last check.
            persistedLocal += ingestor.flush()
        }

        val startNanos = System.nanoTime()
        val producerThreads = (1..producers).map {
            Thread.ofVirtual().name("perf-log-producer-$it").start {
                repeat(recordsPerProducer) { ingestor.enqueue(record()) }
            }
        }
        producerThreads.forEach { it.join() }
        // Producers done: persisted + dropped will converge to total as the queue drains.
        drain.join(5 * 60 * 1000L)
        drainStop.set(1)
        val durationMs = (System.nanoTime() - startNanos) / 1_000_000

        jdbi.setSqlLogger(noopSqlLogger)

        val persisted = counter("epistola.logs.persisted")
        val dropped = counter("epistola.logs.dropped")
        val failures = counter("epistola.logs.persist.failures")
        val latencies = insertNanos.toList()
        val insertCount = latencies.size.toLong()
        val throughput = if (durationMs == 0L) 0.0 else persisted.toDouble() / (durationMs / 1000.0)
        val effectiveBatch = if (insertCount == 0L) 0.0 else persisted.toDouble() / insertCount
        val dropPct = if (total == 0L) 0.0 else dropped.toDouble() * 100.0 / total

        report(scenario, queueCapacity, batchSize, producers, total, durationMs, persisted, dropped, dropPct, failures, insertCount, throughput, effectiveBatch, latencies)

        return Result(persisted, dropped, failures)
    }

    // -- helpers -------------------------------------------------------------

    private fun installInsertLatencyLogger() {
        jdbi.setSqlLogger(object : SqlLogger {
            override fun logBeforeExecution(context: StatementContext) {
                context.define("perf.start", System.nanoTime())
            }

            override fun logAfterExecution(context: StatementContext) {
                val raw = context.rawSql ?: return
                // Only the batched application_log INSERTs — not TRUNCATE or Flyway.
                if (!raw.contains("application_log", ignoreCase = true) || !raw.contains("insert", ignoreCase = true)) return
                val start = context.getAttribute("perf.start") as? Long ?: return
                insertNanos.add(System.nanoTime() - start)
            }

            override fun logException(context: StatementContext, ex: SQLException) = Unit
        })
    }

    private fun record(): ApplicationLogRecord = ApplicationLogRecord(
        id = UUIDv7.generate(),
        occurredAt = OCCURRED_AT,
        level = "INFO",
        logger = "app.epistola.suite.perf.LogIngestWorkload",
        message = MESSAGE,
        thread = "perf-producer",
        tenantKey = "perf-tenant",
        traceId = null,
        spanId = null,
        exception = null,
        attributes = null,
    )

    private fun truncate() {
        jdbi.useHandle<Exception> { it.execute("TRUNCATE application_log") }
    }

    @Suppress("LongParameterList")
    private fun report(
        scenario: String,
        queueCapacity: Int,
        batchSize: Int,
        producers: Int,
        total: Long,
        durationMs: Long,
        persisted: Long,
        dropped: Long,
        dropPct: Double,
        failures: Long,
        insertCount: Long,
        throughput: Double,
        effectiveBatch: Double,
        latencies: List<Long>,
    ) {
        fun us(p: Double) = "%.1f".format(PerfReport.percentile(latencies, p) / 1000.0)
        val stddevUs = "%.1f".format(PerfReport.stddev(latencies) / 1000.0)

        PerfReport.consoleBlock(
            title = "Application-log ingest [$scenario] — batchSize=$batchSize, producers=$producers, N=$total",
            lines = listOf(
                "throughputMsgPerSec" to "%.0f".format(throughput),
                "durationMs" to "$durationMs",
                "persisted" to "$persisted",
                "dropped" to "$dropped (${"%.1f".format(dropPct)}%)",
                "persistFailures" to "$failures",
                "inserts" to "$insertCount",
                "effectiveBatchSize" to "%.1f".format(effectiveBatch),
                "insertLatencyUs p50/p95/p99/p99.9" to "${us(50.0)} / ${us(95.0)} / ${us(99.0)} / ${us(99.9)}",
                "insertLatencyStddevUs" to stddevUs,
            ),
        )

        appendCsv(
            mapOf(
                "timestamp" to PerfReport.nowIso(),
                "test" to "ApplicationLogIngestPerfTest",
                "scenario" to scenario,
                "queueCapacity" to queueCapacity,
                "batchSize" to batchSize,
                "producers" to producers,
                "totalRecords" to total,
                "durationMs" to durationMs,
                "throughputMsgPerSec" to "%.0f".format(throughput),
                "persisted" to persisted,
                "dropped" to dropped,
                "dropPct" to "%.2f".format(dropPct),
                "persistFailures" to failures,
                "inserts" to insertCount,
                "effectiveBatchSize" to "%.1f".format(effectiveBatch),
                "insertP50Us" to us(50.0),
                "insertP95Us" to us(95.0),
                "insertP99Us" to us(99.0),
                "insertP999Us" to us(99.9),
                "insertStddevUs" to stddevUs,
                "hardwareTag" to PerfReport.hardwareTag(),
                "jvm" to PerfReport.jvmTag(),
            ),
        )
    }

    private fun appendCsv(row: Map<String, Any?>) {
        val file = File("build/perf-reports/application-log-ingest.csv")
        file.parentFile?.mkdirs()
        val isNew = !file.exists()
        file.appendText(
            buildString {
                if (isNew) append(row.keys.joinToString(",")).append("\n")
                append(row.values.joinToString(",") { it?.toString() ?: "" }).append("\n")
            },
        )
    }

    private data class Result(val persisted: Long, val dropped: Long, val persistFailures: Long)

    private companion object {
        val OCCURRED_AT: OffsetDateTime = OffsetDateTime.parse("2026-06-12T12:00:00Z")

        // ~200-char message — representative of a real log line, so row width is realistic.
        val MESSAGE = "Processed generation request batch for tenant perf-tenant: " +
            "items=42 rendered=42 failed=0 durationMs=137 correlationId=8f3c1a90-perf-load-test " +
            "queueDepth=0 retries=0 status=COMPLETED note=synthetic-perf-record"
    }
}

/**
 * Slim Spring context for the application-log perf test: DataSource + Flyway +
 * JDBI only. No `@SpringBootApplication`/component scan, so none of the
 * cluster/generation/MCP/JobPoller beans boot. Flyway's default
 * `classpath:db/migration` location migrates the core schema (incl.
 * `application_log`) against the per-context database wired by
 * `TestcontainersConfiguration`.
 */
// Gated behind the `log-ingest-perf` profile: TestApplication's component scan of
// `app.epistola.suite` registers this class but SKIPS it (profile inactive) in every
// other integration test, so it can't pollute their contexts. This perf test
// activates the profile via @ActiveProfiles, so @SpringBootTest(classes=[...]) loads
// it. (A @TestConfiguration would dodge the scan but can't be a @SpringBootTest
// primary config — Spring Boot ignores @TestConfiguration when resolving it.)
@Configuration(proxyBeanMethods = false)
@Profile("log-ingest-perf")
@ImportAutoConfiguration(DataSourceAutoConfiguration::class, FlywayAutoConfiguration::class)
@Import(JdbiConfig::class)
class LogIngestPerfContext {
    // JdbiConfig needs a Jackson-3 (tools.jackson) ObjectMapper; Spring's
    // autoconfigured bean is Jackson 2. Define it exactly like TestApplication.
    @Bean
    fun objectMapper(): ObjectMapper = jsonMapper { addModule(kotlinModule()) }
}

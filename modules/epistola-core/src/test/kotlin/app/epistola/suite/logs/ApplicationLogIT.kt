package app.epistola.suite.logs

import app.epistola.suite.common.UUIDv7
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.logs.queries.ListApplicationLogs
import app.epistola.suite.logs.queries.ListApplicationLogsHandler
import app.epistola.suite.logs.queries.LogPageDirection
import app.epistola.suite.observability.NodeIdentity
import app.epistola.suite.testing.IntegrationTestBase
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Integration cover for application-log capture, retention and the listing query.
 *
 * The ingestor and retention scheduler are constructed directly (the auto-wired
 * beans stay disabled in tests via `epistola.logs.enabled=false`) so capture is
 * exercised deterministically through `flush()` without depending on the
 * background worker's timing, and without the appender attaching to the root
 * logger and polluting the table with framework noise.
 */
class ApplicationLogIT : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    @Autowired
    private lateinit var nodeIdentity: NodeIdentity

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var listHandler: ListApplicationLogsHandler

    private fun newIngestor(
        registry: SimpleMeterRegistry,
        queueCapacity: Int = 10_000,
        batchSize: Int = 200,
    ): ApplicationLogIngestor = ApplicationLogIngestor(
        jdbi = jdbi,
        nodeIdentity = nodeIdentity,
        objectMapper = objectMapper,
        meterRegistry = registry,
        properties = ApplicationLogProperties(queueCapacity = queueCapacity, batchSize = batchSize),
    )

    private fun record(
        logger: String,
        level: String = "INFO",
        tenantKey: String? = null,
        exception: String? = null,
        attributes: Map<String, String>? = null,
    ) = ApplicationLogRecord(
        id = UUIDv7.generate(),
        occurredAt = OffsetDateTime.now(testClock),
        level = level,
        logger = logger,
        message = "msg for $logger",
        thread = "test-thread",
        tenantKey = tenantKey,
        traceId = null,
        spanId = null,
        exception = exception,
        attributes = attributes,
    )

    @Test
    fun `enqueued records are batch-inserted with all columns populated`() {
        val ingestor = newIngestor(SimpleMeterRegistry())
        val logger = "capture.test.${UUID.randomUUID()}"

        ingestor.enqueue(
            record(
                logger = logger,
                level = "ERROR",
                tenantKey = "logcap-tenant",
                exception = "java.lang.RuntimeException: boom\n\tat X",
                attributes = mapOf("requestId" to "req-1"),
            ),
        )
        ingestor.enqueue(record(logger = logger, level = "INFO"))

        val persisted = ingestor.flush()
        assertThat(persisted).isEqualTo(2)

        val rows = rowsForLogger(logger)
        assertThat(rows).hasSize(2)
        val error = rows.first { it.level == "ERROR" }
        assertThat(error.instanceId).isEqualTo(nodeIdentity.nodeId)
        assertThat(error.tenantKey).isEqualTo("logcap-tenant")
        assertThat(error.exception).contains("boom")
        assertThat(error.attributes).contains("req-1")
        val info = rows.first { it.level == "INFO" }
        assertThat(info.tenantKey).isNull()
    }

    @Test
    fun `rate cap sheds a sustained flood and counts it, keeping the DB writes bounded`() {
        val registry = SimpleMeterRegistry()
        // 10 events/s with a generous queue: a tight burst of 100 should admit only
        // ~the bucket's worth (~10) and shed the rest as rate-limited, never dropped.
        val ingestor = ApplicationLogIngestor(
            jdbi = jdbi,
            nodeIdentity = nodeIdentity,
            objectMapper = objectMapper,
            meterRegistry = registry,
            properties = ApplicationLogProperties(queueCapacity = 1_000, maxRatePerSecond = 10),
        )
        val logger = "ratecap.test.${UUID.randomUUID()}"

        repeat(100) { ingestor.enqueue(record(logger = logger)) }
        ingestor.flush()

        fun count(name: String) = registry.find(name).counter()?.count()?.toLong() ?: 0L
        val persisted = count("epistola.logs.persisted")
        val limited = count("epistola.logs.rate.limited")

        assertThat(persisted).`as`("only ~one bucket admitted").isBetween(1L, 30L)
        assertThat(limited).`as`("the flood is shed by the rate cap").isGreaterThan(0L)
        assertThat(count("epistola.logs.dropped")).`as`("queue had room → shed by rate, not overflow").isEqualTo(0L)
        assertThat(persisted + limited).`as`("every record accounted for").isEqualTo(100L)
        assertThat(rowsForLogger(logger)).hasSize(persisted.toInt())
    }

    @Test
    fun `rate cap disabled (maxRatePerSecond=0) admits every event`() {
        val registry = SimpleMeterRegistry()
        val ingestor = ApplicationLogIngestor(
            jdbi = jdbi,
            nodeIdentity = nodeIdentity,
            objectMapper = objectMapper,
            meterRegistry = registry,
            properties = ApplicationLogProperties(queueCapacity = 1_000, maxRatePerSecond = 0),
        )
        val logger = "ratedisabled.test.${UUID.randomUUID()}"

        repeat(500) { ingestor.enqueue(record(logger = logger)) }
        val persisted = ingestor.flush()

        fun count(name: String) = registry.find(name).counter()?.count()?.toLong() ?: 0L
        assertThat(count("epistola.logs.rate.limited")).`as`("rate cap off → nothing shed").isEqualTo(0L)
        assertThat(count("epistola.logs.dropped")).`as`("queue had room").isEqualTo(0L)
        assertThat(persisted).isEqualTo(500)
        assertThat(rowsForLogger(logger)).hasSize(500)
    }

    @Test
    fun `a failing batch is fail-open — counted, worker keeps going, recovers`() {
        val registry = SimpleMeterRegistry()
        val ingestor = newIngestor(registry)
        val failLogger = "failopen.fail.${UUID.randomUUID()}"
        val okLogger = "failopen.ok.${UUID.randomUUID()}"

        // Two records sharing one primary key → the batched INSERT violates the PK and
        // throws. persist() must swallow it, count it, and not wedge the worker.
        val dupId = UUIDv7.generate()
        ingestor.enqueue(record(logger = failLogger).copy(id = dupId))
        ingestor.enqueue(record(logger = failLogger).copy(id = dupId))
        ingestor.flush()

        fun count(name: String) = registry.find(name).counter()?.count()?.toLong() ?: 0L
        assertThat(count("epistola.logs.persist.failures")).`as`("the failing batch is counted").isGreaterThanOrEqualTo(1L)

        // The worker/flush is not wedged: a subsequent good batch still persists.
        ingestor.enqueue(record(logger = okLogger))
        assertThat(ingestor.flush()).isEqualTo(1)
        assertThat(rowsForLogger(okLogger)).hasSize(1)
    }

    @Test
    fun `overflow drops records without blocking and counts them`() {
        val registry = SimpleMeterRegistry()
        val ingestor = newIngestor(registry, queueCapacity = 2)
        val logger = "overflow.test.${UUID.randomUUID()}"

        // Offer 5 into a capacity-2 queue (nothing draining yet) → 3 dropped, no throw.
        repeat(5) { ingestor.enqueue(record(logger = logger)) }

        assertThat(registry.find("epistola.logs.dropped").counter()?.count()).isEqualTo(3.0)

        val persisted = ingestor.flush()
        assertThat(persisted).isEqualTo(2)
        assertThat(rowsForLogger(logger)).hasSize(2)
    }

    @Test
    fun `retention deletes only rows older than the window`() {
        val logger = "retention.test.${UUID.randomUUID()}"
        val now = OffsetDateTime.now(testClock)
        insertRow(logger, occurredAt = now.minusDays(30)) // stale
        insertRow(logger, occurredAt = now.minusHours(1)) // fresh

        val scheduler = ApplicationLogRetentionScheduler(
            jdbi = jdbi,
            meterRegistry = SimpleMeterRegistry(),
            properties = ApplicationLogProperties(retentionDays = 7),
        )

        val deleted = withMediator { scheduler.deleteExpired() }

        assertThat(deleted).isGreaterThanOrEqualTo(1)
        val remaining = rowsForLogger(logger)
        assertThat(remaining).hasSize(1)
        assertThat(remaining.single().occurredAt).isAfter(now.minusDays(2))
    }

    @Test
    fun `query returns the tenant's rows plus system rows and applies filters`() {
        val tenantA = "logq-a"
        val tenantB = "logq-b"
        val logger = "query.test.${UUID.randomUUID()}"

        insertRow(logger, tenantKey = tenantA, level = "INFO")
        insertRow(logger, tenantKey = tenantA, level = "ERROR")
        insertRow(logger, tenantKey = null, level = "WARN") // system row
        insertRow(logger, tenantKey = tenantB, level = "ERROR") // other tenant — must be excluded
        val unrelatedLogger = "unrelated.${UUID.randomUUID()}"
        insertRow(unrelatedLogger, tenantKey = tenantA, level = "ERROR") // different logger — must be excluded by the filter

        // Tenant scoping: own rows + system row, never tenantB.
        val all = listHandler.handle(ListApplicationLogs(tenantId = TenantKey.of(tenantA), logger = logger))
        assertThat(all.map { it.tenantKey?.value }).containsExactlyInAnyOrder(tenantA, tenantA, null)

        // Level filter.
        val errors = listHandler.handle(
            ListApplicationLogs(tenantId = TenantKey.of(tenantA), logger = logger, level = "ERROR"),
        )
        assertThat(errors).hasSize(1)
        assertThat(errors.single().level).isEqualTo("ERROR")
        assertThat(errors.single().tenantKey?.value).isEqualTo(tenantA)

        // Logger substring filter excludes the "other." logger.
        val byLogger = listHandler.handle(ListApplicationLogs(tenantId = TenantKey.of(tenantA), logger = logger))
        assertThat(byLogger.map { it.logger }).allMatch { it == logger }
    }

    // -- helpers -------------------------------------------------------------

    private data class Row(
        val level: String,
        val logger: String,
        val instanceId: String,
        val tenantKey: String?,
        val exception: String?,
        val attributes: String?,
        val occurredAt: OffsetDateTime,
    )

    private fun rowsForLogger(logger: String): List<Row> = jdbi.withHandle<List<Row>, Exception> { handle ->
        handle.createQuery(
            """
            SELECT level, logger, instance_id, tenant_key, exception, attributes::text AS attributes, occurred_at
            FROM application_log WHERE logger = :logger ORDER BY occurred_at
            """,
        )
            .bind("logger", logger)
            .map { rs, _ ->
                Row(
                    level = rs.getString("level"),
                    logger = rs.getString("logger"),
                    instanceId = rs.getString("instance_id"),
                    tenantKey = rs.getString("tenant_key"),
                    exception = rs.getString("exception"),
                    attributes = rs.getString("attributes"),
                    occurredAt = rs.getObject("occurred_at", OffsetDateTime::class.java),
                )
            }
            .list()
    }

    private fun insertRow(
        logger: String,
        tenantKey: String? = null,
        level: String = "INFO",
        message: String = "m",
        occurredAt: OffsetDateTime = OffsetDateTime.now(testClock),
    ) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO application_log (id, occurred_at, level, logger, message, instance_id, tenant_key)
                VALUES (:id, :occurredAt, :level, :logger, :message, :instanceId, :tenantKey)
                """,
            )
                .bind("id", UUIDv7.generate())
                .bind("occurredAt", occurredAt)
                .bind("level", level)
                .bind("logger", logger)
                .bind("message", message)
                .bind("instanceId", nodeIdentity.nodeId)
                .bind("tenantKey", tenantKey)
                .execute()
        }
    }

    @Test
    fun `keyset pagination pages older then newer, newest-first, without gaps`() {
        val logger = "keyset.${UUID.randomUUID()}"
        val tenant = TenantKey.of("keyset-tenant")
        val base = OffsetDateTime.now(testClock).withNano(0)
        (1..5).forEach { i -> insertRow(logger, message = "e$i", occurredAt = base.plusMinutes(i.toLong())) }

        fun page(direction: LogPageDirection, cursor: ApplicationLogEntry?) = listHandler.handle(
            ListApplicationLogs(
                tenantId = tenant,
                logger = logger,
                limit = 2,
                direction = direction,
                cursorOccurredAt = cursor?.occurredAt,
                cursorId = cursor?.id,
            ),
        )

        // Newest-first default page.
        val p1 = page(LogPageDirection.OLDER, null)
        assertThat(p1.map { it.message }).containsExactly("e5", "e4")

        // Load older from the oldest shown (e4) → the next two older, still newest-first.
        val p2 = page(LogPageDirection.OLDER, p1.last())
        assertThat(p2.map { it.message }).containsExactly("e3", "e2")

        // Load newer from the oldest shown (e2) → the two immediately newer, newest-first.
        val newer = page(LogPageDirection.NEWER, p2.last())
        assertThat(newer.map { it.message }).containsExactly("e4", "e3")

        // Nothing newer than the newest row.
        assertThat(page(LogPageDirection.NEWER, p1.first())).isEmpty()
    }

    @Test
    fun `message search matches case-insensitively`() {
        val logger = "search.${UUID.randomUUID()}"
        insertRow(logger, message = "Connection TIMEOUT to upstream")
        insertRow(logger, message = "ordinary heartbeat")

        val hits = listHandler.handle(
            ListApplicationLogs(tenantId = TenantKey.of("search-tenant"), logger = logger, search = "timeout"),
        )
        assertThat(hits).hasSize(1)
        assertThat(hits.single().message).contains("TIMEOUT")
    }
}

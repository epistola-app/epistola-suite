package app.epistola.suite.logs

import app.epistola.suite.observability.NodeIdentity
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.filter.ThresholdFilter
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PreDestroy
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Owns the bounded queue and the single background worker that batch-inserts
 * captured log events into `application_log`.
 *
 * Design constraints (issue #525):
 *  - **Non-blocking:** [enqueue] only `offer`s to a bounded queue; on overflow it
 *    drops the event and increments `epistola.logs.dropped`. It never blocks the
 *    thread that logged.
 *  - **Batched:** the worker drains up to `batchSize` records per JDBI
 *    `prepareBatch` insert.
 *  - **Fail-open:** a failing batch is dropped (and counted), never retried into
 *    a tight loop, and never allowed to kill the worker. A DB outage degrades
 *    logging silently rather than breaking requests.
 *  - **No recursion:** the appender ignores this package's own loggers, so the
 *    failure log below cannot re-enter the queue.
 *
 * The appender is attached programmatically on [ApplicationReadyEvent] (after
 * Flyway, mirroring `PartitionMaintenanceScheduler`) and detached on shutdown,
 * so there is no "before Spring is ready" window where events would be dropped
 * for lack of a sink.
 */
@Component
@ConditionalOnProperty(prefix = "epistola.logs", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class ApplicationLogIngestor(
    private val jdbi: Jdbi,
    private val nodeIdentity: NodeIdentity,
    private val objectMapper: ObjectMapper,
    meterRegistry: MeterRegistry,
    private val properties: ApplicationLogProperties,
) {
    // This logger is excluded from capture by the appender's recursion guard
    // (ApplicationLogAppender.INGESTOR_LOGGER), so persistence-failure warnings below
    // cannot feed back into the queue.
    private val logger = LoggerFactory.getLogger(ApplicationLogIngestor::class.java)

    private val queue = ArrayBlockingQueue<ApplicationLogRecord>(properties.queueCapacity.coerceAtLeast(1))

    private val dropped = Counter.builder("epistola.logs.dropped")
        .description("Application log events dropped because the capture queue was full")
        .register(meterRegistry)
    private val rateLimited = Counter.builder("epistola.logs.rate.limited")
        .description("Application log events shed by the per-second rate cap (log-bomb guard)")
        .register(meterRegistry)
    private val persisted = Counter.builder("epistola.logs.persisted")
        .description("Application log events persisted to the table")
        .register(meterRegistry)
    private val persistFailures = Counter.builder("epistola.logs.persist.failures")
        .description("Application log batch inserts that failed (events dropped)")
        .register(meterRegistry)

    // Token bucket guarding against a log bomb. Capacity = one second's worth, so
    // normal bursts pass but a sustained flood is shed at `maxRatePerSecond`.
    private val rateLimitEnabled = properties.maxRatePerSecond > 0
    private val maxTokens = properties.maxRatePerSecond.toDouble()
    private var tokens = maxTokens
    private var lastRefillNanos = System.nanoTime()

    @Volatile
    private var running = false
    private var worker: Thread? = null
    private var appender: ApplicationLogAppender? = null

    /** Offer a record for asynchronous persistence. Never blocks; drops on overflow or rate cap. */
    fun enqueue(record: ApplicationLogRecord) {
        if (!rateAllows()) {
            rateLimited.increment()
            return
        }
        if (!queue.offer(record)) {
            dropped.increment()
        }
    }

    /**
     * Token-bucket admission, refilled by elapsed wall time. Synchronized because
     * many request threads call [enqueue] concurrently; the body is a handful of
     * arithmetic ops, and under a bomb (the case this protects) shedding load is the
     * point, so brief serialization here is acceptable. `System.nanoTime()` is a
     * monotonic elapsed-time source (not application time), so it is the right clock.
     */
    @Synchronized
    private fun rateAllows(): Boolean {
        if (!rateLimitEnabled) return true
        val now = System.nanoTime()
        val elapsedNanos = now - lastRefillNanos
        if (elapsedNanos > 0) {
            val refill = elapsedNanos.toDouble() * maxTokens / 1_000_000_000.0
            if (refill >= 1.0) {
                tokens = (tokens + refill).coerceAtMost(maxTokens)
                lastRefillNanos = now
            }
        }
        return if (tokens >= 1.0) {
            tokens -= 1.0
            true
        } else {
            false
        }
    }

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        if (running) return
        running = true
        worker = Thread {
            drainLoop()
        }.apply {
            name = "application-log-ingestor"
            isDaemon = true
            start()
        }
        attachAppender()
        logger.info("Application log capture started (level={}, queueCapacity={})", properties.level, properties.queueCapacity)
    }

    @PreDestroy
    fun stop() {
        if (!running) return
        running = false
        detachAppender()
        // The worker wakes at most every flushIntervalMs; give it that plus slack
        // to flush whatever is queued before the connection pool closes.
        worker?.join(properties.flushIntervalMs + 2_000)
        worker = null
    }

    private fun attachAppender() {
        val loggerContext = LoggerFactory.getILoggerFactory() as? LoggerContext ?: run {
            logger.warn("Logger factory is not Logback; application log capture disabled")
            return
        }
        val threshold = ThresholdFilter().apply {
            setLevel(Level.toLevel(properties.level, Level.INFO).levelStr)
            start()
        }
        val newAppender = ApplicationLogAppender(::enqueue).apply {
            context = loggerContext
            name = "EPISTOLA_APPLICATION_LOG"
            addFilter(threshold)
            start()
        }
        loggerContext.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(newAppender)
        appender = newAppender
    }

    private fun detachAppender() {
        val current = appender ?: return
        (LoggerFactory.getILoggerFactory() as? LoggerContext)
            ?.getLogger(Logger.ROOT_LOGGER_NAME)
            ?.detachAppender(current)
        current.stop()
        appender = null
    }

    private fun drainLoop() {
        while (running) {
            try {
                // Block until at least one record is available (or time out to re-check `running`).
                val first = queue.poll(properties.flushIntervalMs, TimeUnit.MILLISECONDS) ?: continue
                val batch = ArrayList<ApplicationLogRecord>(properties.batchSize)
                batch.add(first)
                queue.drainTo(batch, properties.batchSize - 1)
                persist(batch)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        flush() // final drain on shutdown
    }

    /**
     * Synchronously drain and persist everything currently queued, returning the
     * number of records persisted. Used as the shutdown final-drain and by tests
     * to assert capture deterministically without depending on the worker's timing.
     */
    fun flush(): Int {
        var total = 0
        while (true) {
            val batch = ArrayList<ApplicationLogRecord>(properties.batchSize)
            queue.drainTo(batch, properties.batchSize)
            if (batch.isEmpty()) break
            persist(batch)
            total += batch.size
        }
        return total
    }

    /** Persist a batch, fail-open: a DB error drops the batch (and counts it), never throws. */
    private fun persist(batch: List<ApplicationLogRecord>) {
        if (batch.isEmpty()) return
        try {
            insertBatch(batch)
            persisted.increment(batch.size.toDouble())
        } catch (e: Exception) {
            // Fail-open: never let the worker die and never break the request that logged.
            persistFailures.increment()
            logger.warn("Application log batch insert failed; dropping {} record(s): {}", batch.size, e.message)
        }
    }

    private fun insertBatch(records: List<ApplicationLogRecord>) {
        jdbi.useHandle<Exception> { handle ->
            val batch = handle.prepareBatch(
                """
                INSERT INTO application_log
                    (id, occurred_at, level, logger, message, thread, instance_id,
                     tenant_key, trace_id, span_id, exception, attributes)
                VALUES
                    (:id, :occurredAt, :level, :logger, :message, :thread, :instanceId,
                     :tenantKey, :traceId, :spanId, :exception, CAST(:attributes AS JSONB))
                """,
            )
            for (record in records) {
                batch
                    .bind("id", record.id)
                    .bind("occurredAt", record.occurredAt)
                    .bind("level", record.level)
                    .bind("logger", record.logger)
                    .bind("message", record.message)
                    .bind("thread", record.thread)
                    .bind("instanceId", nodeIdentity.nodeId)
                    .bind("tenantKey", record.tenantKey)
                    .bind("traceId", record.traceId)
                    .bind("spanId", record.spanId)
                    .bind("exception", record.exception)
                    .bind("attributes", record.attributes?.let { objectMapper.writeValueAsString(it) })
                    .add()
            }
            batch.execute()
        }
    }
}

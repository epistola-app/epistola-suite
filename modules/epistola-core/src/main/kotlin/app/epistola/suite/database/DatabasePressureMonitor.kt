// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.database

import app.epistola.suite.documents.JobPollingProperties
import app.epistola.suite.time.EpistolaClock
import com.zaxxer.hikari.HikariDataSource
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.binder.MeterBinder
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.sql.SQLException
import java.time.Duration
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource

/**
 * A small, process-local view of database pressure.
 *
 * JDBI feeds it statement round-trip measurements; Hikari contributes whether
 * callers are waiting for a pooled connection.  Values are aggregated only —
 * neither SQL nor bind values ever become metric tags or retained state.
 */
@Component
class DatabasePressureMonitor(
    private val properties: JobPollingProperties,
    dataSource: DataSource,
) : DatabasePressureSource,
    MeterBinder {
    private data class Observation(val atMs: Long, val durationMs: Long)
    private data class Timers(val success: Timer, val failure: Timer)

    private val logger = LoggerFactory.getLogger(javaClass)
    private val observations = ArrayDeque<Observation>()
    private var lastCriticalFailureAtMs: Long = Long.MIN_VALUE
    private val hikari = dataSource as? HikariDataSource
    private val timersByRegistry = ConcurrentHashMap<MeterRegistry, Timers>()

    init {
        if (hikari != null) {
            logger.info("Database pressure monitor: Hikari pool-waiter detection is active")
        } else {
            logger.warn(
                "Database pressure monitor: DataSource is not a HikariDataSource ({}) — " +
                    "pool-waiter detection is disabled; pressure detection relies on statement latency only",
                dataSource::class.qualifiedName,
            )
        }
    }

    /**
     * Spring invokes this after registries have completed their own bootstrap.
     * In particular, this must not resolve a registry from the JDBI construction
     * path: common metric tags read installation metadata through that same JDBI
     * instance.
     */
    override fun bindTo(registry: MeterRegistry) {
        timersByRegistry.computeIfAbsent(registry) {
            Timers(
                success = buildTimer(registry, "success"),
                failure = buildTimer(registry, "failure"),
            )
        }
    }

    fun recordSuccess(duration: Duration) = record(duration) { it.success }

    fun recordFailure(duration: Duration, error: SQLException) {
        record(duration) { it.failure }
        if (error.sqlState?.let(::isCriticalSqlState) == true) {
            synchronized(this) {
                lastCriticalFailureAtMs = EpistolaClock.instant().toEpochMilli()
            }
        }
    }

    override fun snapshot(nowMs: Long): DatabasePressureSnapshot = synchronized(this) {
        val config = properties.databasePressure
        prune(nowMs, config.observationWindowMs)
        val durations = observations.map { it.durationMs }.sorted()
        val p95 = durations.takeIf { it.isNotEmpty() }?.let { sorted ->
            sorted[((sorted.size * 95 + 99) / 100 - 1).coerceIn(0, sorted.lastIndex)]
        }
        val poolWaiters = hikari?.hikariPoolMXBean?.threadsAwaitingConnection ?: 0
        DatabasePressureSnapshot(
            sampleCount = durations.size,
            p95StatementLatencyMs = p95,
            poolWaiters = poolWaiters,
            criticalFailureObserved = lastCriticalFailureAtMs != Long.MIN_VALUE &&
                nowMs - lastCriticalFailureAtMs <= config.observationWindowMs,
        )
    }

    private fun buildTimer(registry: MeterRegistry, outcome: String): Timer = Timer.builder("epistola.database.statement.duration")
        .description("JDBI database statement execution duration")
        .tag("outcome", outcome)
        .register(registry)

    private fun record(duration: Duration, timer: (Timers) -> Timer) {
        val safeDuration = duration.coerceAtLeast(Duration.ZERO)
        timersByRegistry.values.forEach { timer(it).record(safeDuration) }
        synchronized(this) {
            val nowMs = EpistolaClock.instant().toEpochMilli()
            observations.addLast(Observation(nowMs, safeDuration.toMillis()))
            prune(nowMs, properties.databasePressure.observationWindowMs)
        }
    }

    private fun prune(nowMs: Long, windowMs: Long) {
        val cutoff = nowMs - windowMs.coerceAtLeast(1)
        while (true) {
            val oldest = observations.firstOrNull() ?: break
            if (oldest.atMs >= cutoff) break
            observations.removeFirst()
        }
    }

    private fun isCriticalSqlState(sqlState: String): Boolean = sqlState.startsWith("08") || sqlState == "57014" || sqlState == "53300"
}

fun interface DatabasePressureSource {
    fun snapshot(nowMs: Long): DatabasePressureSnapshot
}

data class DatabasePressureSnapshot(
    val sampleCount: Int,
    val p95StatementLatencyMs: Long?,
    val poolWaiters: Int,
    val criticalFailureObserved: Boolean,
)

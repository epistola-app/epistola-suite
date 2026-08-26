// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.database

import app.epistola.suite.documents.JobPollingProperties
import app.epistola.suite.time.EpistolaClock
import com.zaxxer.hikari.HikariDataSource
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import java.sql.SQLException
import java.time.Duration
import java.util.ArrayDeque
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
    private val meterRegistryProvider: ObjectProvider<MeterRegistry>,
    dataSource: DataSource,
) : DatabasePressureSource {
    private data class Observation(val atMs: Long, val durationMs: Long)

    private val logger = LoggerFactory.getLogger(javaClass)
    private val observations = ArrayDeque<Observation>()
    private var lastCriticalFailureAtMs: Long = Long.MIN_VALUE
    private val hikari = dataSource as? HikariDataSource
    private val successTimer = buildTimer("success")
    private val failureTimer = buildTimer("failure")

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

    fun recordSuccess(duration: Duration) = record(duration, successTimer)

    fun recordFailure(duration: Duration, error: SQLException) {
        record(duration, failureTimer)
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

    private fun buildTimer(outcome: String): Timer = Timer.builder("epistola.database.statement.duration")
        .description("JDBI database statement execution duration")
        .tag("outcome", outcome)
        .register(meterRegistryProvider.getObject())

    private fun record(duration: Duration, timer: Timer) {
        val safeDuration = duration.coerceAtLeast(Duration.ZERO)
        timer.record(safeDuration)
        synchronized(this) {
            val nowMs = EpistolaClock.instant().toEpochMilli()
            observations.addLast(Observation(nowMs, safeDuration.toMillis()))
            prune(nowMs, properties.databasePressure.observationWindowMs)
        }
    }

    private fun prune(nowMs: Long, windowMs: Long) {
        val cutoff = nowMs - windowMs.coerceAtLeast(1)
        while ((observations.firstOrNull()?.atMs ?: Long.MAX_VALUE) < cutoff) {
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

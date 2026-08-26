// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents.batch

import app.epistola.suite.documents.JobPollingProperties
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicInteger

/** States exposed through [epistola.generation.database.pressure.state]. */
enum class DatabasePressureState(val metricValue: Int) {
    NORMAL(0),
    THROTTLED(1),
    PAUSED(2),
    RECOVERING(3),
}

/**
 * Hysteretic admission controller for background rendering.
 *
 * It controls only future claims.  A lower limit never interrupts a document
 * that is already rendering, preserving the generator's at-least-once and
 * cancellation semantics.
 */
@Component
class DatabasePressureAdmissionController(
    private val properties: JobPollingProperties,
    private val monitor: DatabasePressureSource,
    meterRegistry: MeterRegistry,
) {
    private val effectiveLimit = AtomicInteger(properties.maxConcurrentJobs)
    private val stateValue = AtomicInteger(DatabasePressureState.NORMAL.metricValue)
    private var state = DatabasePressureState.NORMAL
    private var healthySinceMs: Long? = null
    private var lastBackoffMs = Long.MIN_VALUE
    private var lastRecoveryStepMs = Long.MIN_VALUE

    private val throttledCounter = meterRegistry.counter("epistola.generation.database_pressure.throttled.total")
    private val pausedCounter = meterRegistry.counter("epistola.generation.database_pressure.paused.total")

    init {
        meterRegistry.gauge("epistola.generation.concurrency.effective", effectiveLimit) { it.get().toDouble() }
        meterRegistry.gauge("epistola.generation.database_pressure.state", stateValue) { it.get().toDouble() }
    }

    @Synchronized
    fun effectiveLimit(nowMs: Long = System.currentTimeMillis()): Int {
        val configuredMaximum = properties.maxConcurrentJobs.coerceAtLeast(1)
        val config = properties.databasePressure
        if (!config.enabled) {
            transition(DatabasePressureState.NORMAL)
            effectiveLimit.set(configuredMaximum)
            return configuredMaximum
        }

        val snapshot = monitor.snapshot(nowMs)
        if (snapshot.criticalFailureObserved) {
            healthySinceMs = null
            effectiveLimit.set(0)
            transition(DatabasePressureState.PAUSED)
            return 0
        }

        val pressured = snapshot.poolWaiters > 0 ||
            (
                snapshot.sampleCount >= config.minimumSamples &&
                    (snapshot.p95StatementLatencyMs ?: 0) >= config.slowStatementThresholdMs
                )
        if (pressured) {
            healthySinceMs = null
            if (lastBackoffMs == Long.MIN_VALUE || nowMs - lastBackoffMs >= config.backoffIntervalMs) {
                val minimum = config.minimumConcurrentJobs.coerceIn(1, configuredMaximum)
                val current = effectiveLimit.get().coerceAtMost(configuredMaximum)
                val reduced = if (current == 0) minimum else ((current + 1) / 2).coerceAtLeast(minimum)
                if (reduced < current || state == DatabasePressureState.PAUSED) {
                    effectiveLimit.set(reduced)
                    throttledCounter.increment()
                }
                lastBackoffMs = nowMs
            }
            transition(DatabasePressureState.THROTTLED)
            return effectiveLimit.get()
        }

        val healthy = snapshot.poolWaiters == 0 &&
            snapshot.sampleCount >= config.minimumSamples &&
            (snapshot.p95StatementLatencyMs ?: Long.MAX_VALUE) < config.recoveryStatementThresholdMs
        if (!healthy) return effectiveLimit.get().coerceAtMost(configuredMaximum)

        val healthySince = healthySinceMs ?: nowMs.also { healthySinceMs = it }
        if (state == DatabasePressureState.NORMAL) {
            effectiveLimit.set(configuredMaximum)
            return configuredMaximum
        }
        if (nowMs - healthySince < config.recoveryPeriodMs) return effectiveLimit.get()

        val minimum = config.minimumConcurrentJobs.coerceIn(1, configuredMaximum)
        if (state == DatabasePressureState.PAUSED) {
            effectiveLimit.set(minimum)
            lastRecoveryStepMs = nowMs
            transition(DatabasePressureState.RECOVERING)
            return minimum
        }
        if (state != DatabasePressureState.RECOVERING) {
            lastRecoveryStepMs = nowMs
            transition(DatabasePressureState.RECOVERING)
            return effectiveLimit.get()
        }
        if (nowMs - lastRecoveryStepMs >= config.recoveryStepIntervalMs) {
            val raised = (effectiveLimit.get() + 1).coerceAtMost(configuredMaximum)
            effectiveLimit.set(raised)
            lastRecoveryStepMs = nowMs
        }
        transition(if (effectiveLimit.get() == configuredMaximum) DatabasePressureState.NORMAL else DatabasePressureState.RECOVERING)
        return effectiveLimit.get()
    }

    private fun transition(next: DatabasePressureState) {
        if (state == next) return
        if (next == DatabasePressureState.PAUSED) pausedCounter.increment()
        state = next
        stateValue.set(next.metricValue)
    }
}

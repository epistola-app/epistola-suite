// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents.batch

import app.epistola.suite.database.DatabasePressureSnapshot
import app.epistola.suite.database.DatabasePressureSource
import app.epistola.suite.documents.DatabasePressureProperties
import app.epistola.suite.documents.JobPollingProperties
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

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
    private val pressureSource: DatabasePressureSource,
    meterRegistry: MeterRegistry,
) {
    private val effectiveLimit = AtomicInteger(properties.maxConcurrentJobs)
    private val state = AtomicReference(DatabasePressureState.NORMAL)
    private var healthySinceMs: Long? = null
    private var lastBackoffMs = Long.MIN_VALUE
    private var lastRecoveryStepMs = Long.MIN_VALUE

    private val throttledCounter = meterRegistry.counter("epistola.generation.database_pressure.throttled.total")
    private val pausedCounter = meterRegistry.counter("epistola.generation.database_pressure.paused.total")

    init {
        meterRegistry.gauge("epistola.generation.concurrency.effective", effectiveLimit) { it.get().toDouble() }
        meterRegistry.gauge("epistola.generation.database_pressure.state", state) { it.get().metricValue.toDouble() }
    }

    @Synchronized
    fun effectiveLimit(nowMs: Long = System.currentTimeMillis()): Int {
        val configuredMaximum = properties.maxConcurrentJobs.coerceAtLeast(1)
        val config = properties.databasePressure
        if (!config.enabled) return disabled(configuredMaximum)

        val snapshot = pressureSource.snapshot(nowMs)
        if (snapshot.criticalFailureObserved) return pause()

        val pressured = isPressured(snapshot, config)
        val healthy = isHealthy(snapshot, config)

        return when (state.get()) {
            DatabasePressureState.NORMAL -> handleNormal(nowMs, config, configuredMaximum, pressured, healthy)
            DatabasePressureState.THROTTLED ->
                handleActive(nowMs, config, configuredMaximum, pressured, healthy, fromPaused = false)
            DatabasePressureState.PAUSED ->
                handleActive(nowMs, config, configuredMaximum, pressured, healthy, fromPaused = true)
            DatabasePressureState.RECOVERING ->
                handleActive(nowMs, config, configuredMaximum, pressured, healthy, fromPaused = false)
        }
    }

    private fun disabled(configuredMaximum: Int): Int {
        transition(DatabasePressureState.NORMAL)
        effectiveLimit.set(configuredMaximum)
        return configuredMaximum
    }

    private fun pause(): Int {
        healthySinceMs = null
        effectiveLimit.set(0)
        transition(DatabasePressureState.PAUSED)
        return 0
    }

    private fun isPressured(snapshot: DatabasePressureSnapshot, config: DatabasePressureProperties): Boolean = snapshot.poolWaiters > 0 ||
        (
            snapshot.sampleCount >= config.minimumSamples &&
                (snapshot.p95StatementLatencyMs ?: 0) >= config.slowStatementThresholdMs
            )

    private fun isHealthy(snapshot: DatabasePressureSnapshot, config: DatabasePressureProperties): Boolean = snapshot.poolWaiters == 0 &&
        snapshot.sampleCount >= config.minimumSamples &&
        (snapshot.p95StatementLatencyMs ?: Long.MAX_VALUE) < config.recoveryStatementThresholdMs

    /**
     * NORMAL never enters the recovery ladder in [recover] — once healthy it snaps straight
     * back to the configured maximum, since there was nothing to recover from.
     */
    private fun handleNormal(
        nowMs: Long,
        config: DatabasePressureProperties,
        configuredMaximum: Int,
        pressured: Boolean,
        healthy: Boolean,
    ): Int {
        if (pressured) return throttle(nowMs, config, configuredMaximum, fromPaused = false)
        if (!healthy) return effectiveLimit.get().coerceAtMost(configuredMaximum)
        healthySinceMs = healthySinceMs ?: nowMs
        effectiveLimit.set(configuredMaximum)
        return configuredMaximum
    }

    /** Shared by THROTTLED, PAUSED, and RECOVERING — identical apart from [fromPaused]. */
    private fun handleActive(
        nowMs: Long,
        config: DatabasePressureProperties,
        configuredMaximum: Int,
        pressured: Boolean,
        healthy: Boolean,
        fromPaused: Boolean,
    ): Int {
        if (pressured) return throttle(nowMs, config, configuredMaximum, fromPaused)
        if (!healthy) return effectiveLimit.get().coerceAtMost(configuredMaximum)
        return recover(nowMs, config, configuredMaximum)
    }

    private fun throttle(nowMs: Long, config: DatabasePressureProperties, configuredMaximum: Int, fromPaused: Boolean): Int {
        healthySinceMs = null
        if (lastBackoffMs == Long.MIN_VALUE || nowMs - lastBackoffMs >= config.backoffIntervalMs) {
            val minimum = config.minimumConcurrentJobs.coerceIn(1, configuredMaximum)
            val current = effectiveLimit.get().coerceAtMost(configuredMaximum)
            val reduced = if (current == 0) minimum else ((current + 1) / 2).coerceAtLeast(minimum)
            if (reduced < current || fromPaused) {
                effectiveLimit.set(reduced)
                throttledCounter.increment()
            }
            lastBackoffMs = nowMs
        }
        transition(DatabasePressureState.THROTTLED)
        return effectiveLimit.get()
    }

    private fun recover(nowMs: Long, config: DatabasePressureProperties, configuredMaximum: Int): Int {
        val healthySince = healthySinceMs ?: nowMs.also { healthySinceMs = it }
        if (nowMs - healthySince < config.recoveryPeriodMs) return effectiveLimit.get()

        val minimum = config.minimumConcurrentJobs.coerceIn(1, configuredMaximum)
        if (state.get() == DatabasePressureState.PAUSED) {
            effectiveLimit.set(minimum)
            lastRecoveryStepMs = nowMs
            transition(DatabasePressureState.RECOVERING)
            return minimum
        }
        if (state.get() != DatabasePressureState.RECOVERING) {
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
        if (state.get() == next) return
        if (next == DatabasePressureState.PAUSED) pausedCounter.increment()
        state.set(next)
    }
}

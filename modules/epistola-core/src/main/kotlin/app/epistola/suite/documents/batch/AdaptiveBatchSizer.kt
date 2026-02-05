package app.epistola.suite.documents.batch

import app.epistola.suite.documents.JobPollingProperties
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/**
 * Dynamically adjusts batch size based on job processing time using Exponential Moving Average (EMA).
 *
 * Tracks average processing time from job claim to completion and adapts batch size:
 * - **Fast processing** (< fastThresholdMs): Increase batch size to claim more jobs per poll
 * - **Normal processing** (between thresholds): Maintain current batch size
 * - **Slow processing** (> slowThresholdMs): Decrease batch size to reduce system load
 *
 * Metrics are exposed via Micrometer for monitoring and alerting.
 */
@Component
class AdaptiveBatchSizer(
    pollingProperties: JobPollingProperties,
    private val meterRegistry: MeterRegistry,
) {
    private val properties = pollingProperties.adaptiveBatch

    // EMA of job processing time (milliseconds)
    private val emaProcessingTimeMs = AtomicLong(0)

    // Current batch size
    private val currentBatchSize = AtomicInteger(properties.minBatchSize)

    // Alpha for EMA calculation (0.2 = 20% weight to new value, 80% to historical average)
    private val alpha = 0.2

    init {
        // Register Micrometer gauges for observability
        meterRegistry.gauge("epistola.jobs.processing_time_ema_ms", emaProcessingTimeMs) { it.get().toDouble() }
        meterRegistry.gauge("epistola.jobs.batch_size", currentBatchSize) { it.get().toDouble() }
    }

    /**
     * Record job completion and update EMA.
     *
     * Updates the exponential moving average of processing time and adjusts batch size
     * based on the new average.
     *
     * @param durationMs Job processing duration in milliseconds (from claim to completion)
     */
    fun recordJobCompletion(durationMs: Long) {
        val currentEma = emaProcessingTimeMs.get()
        val newEma = if (currentEma == 0L) {
            // First data point - use actual value as initial EMA
            durationMs
        } else {
            // EMA formula: newEMA = alpha * current + (1 - alpha) * oldEMA
            (alpha * durationMs + (1 - alpha) * currentEma).toLong()
        }

        emaProcessingTimeMs.set(newEma)
        adjustBatchSize(newEma)
    }

    /**
     * Adjust batch size based on current EMA.
     *
     * Applies adaptive algorithm:
     * - Fast processing: Increase batch size (system has capacity)
     * - Slow processing: Decrease batch size (system under load)
     * - Normal processing: Maintain batch size (system stable)
     */
    private fun adjustBatchSize(emaMs: Long) {
        val current = currentBatchSize.get()
        val newSize = when {
            emaMs < properties.fastThresholdMs -> {
                // Processing is fast, increase batch size (more aggressive claiming)
                min(current + 1, properties.maxBatchSize)
            }
            emaMs > properties.slowThresholdMs -> {
                // Processing is slow, decrease batch size (back off to avoid overload)
                max(current - 1, properties.minBatchSize)
            }
            else -> {
                // Processing is normal, maintain current batch size
                current
            }
        }

        if (newSize != current) {
            currentBatchSize.set(newSize)
        }
    }

    /**
     * Get current batch size for polling.
     *
     * @return Current adaptive batch size (between minBatchSize and maxBatchSize)
     */
    fun getCurrentBatchSize(): Int = currentBatchSize.get()
}

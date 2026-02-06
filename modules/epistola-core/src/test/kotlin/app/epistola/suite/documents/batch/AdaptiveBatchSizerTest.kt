package app.epistola.suite.documents.batch

import app.epistola.suite.documents.AdaptiveBatchProperties
import app.epistola.suite.documents.JobPollingProperties
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdaptiveBatchSizerTest {

    private val meterRegistry = SimpleMeterRegistry()

    @Test
    fun `increases batch size when processing is fast`() {
        val pollingProperties = JobPollingProperties(
            adaptiveBatch = AdaptiveBatchProperties(
                minBatchSize = 1,
                maxBatchSize = 10,
                fastThresholdMs = 2000,
                slowThresholdMs = 5000,
            ),
        )
        val sizer = AdaptiveBatchSizer(pollingProperties, meterRegistry)

        val initialBatchSize = sizer.getCurrentBatchSize()
        assertEquals(1, initialBatchSize, "Should start at minBatchSize")

        // Record 10 fast jobs (500ms each, well below fastThreshold)
        repeat(10) {
            sizer.recordJobCompletion(500)
        }

        // Batch size should increase toward maxBatchSize
        val newBatchSize = sizer.getCurrentBatchSize()
        assertTrue(
            newBatchSize > initialBatchSize,
            "Batch size should increase when processing is fast (was $initialBatchSize, now $newBatchSize)",
        )
    }

    @Test
    fun `decreases batch size when processing is slow`() {
        val pollingProperties = JobPollingProperties(
            adaptiveBatch = AdaptiveBatchProperties(
                minBatchSize = 1,
                maxBatchSize = 10,
                fastThresholdMs = 2000,
                slowThresholdMs = 5000,
            ),
        )
        val sizer = AdaptiveBatchSizer(pollingProperties, meterRegistry)

        // First, ramp up batch size with fast jobs
        repeat(20) {
            sizer.recordJobCompletion(500)
        }
        val highBatchSize = sizer.getCurrentBatchSize()
        assertTrue(highBatchSize > 1, "Batch size should have increased")

        // Now record slow jobs (7000ms each, well above slowThreshold)
        repeat(30) {
            sizer.recordJobCompletion(7000)
        }

        // Batch size should decrease toward minBatchSize
        val newBatchSize = sizer.getCurrentBatchSize()
        assertTrue(
            newBatchSize < highBatchSize,
            "Batch size should decrease when processing is slow (was $highBatchSize, now $newBatchSize)",
        )
    }

    @Test
    fun `maintains batch size when processing is normal`() {
        val pollingProperties = JobPollingProperties(
            adaptiveBatch = AdaptiveBatchProperties(
                minBatchSize = 1,
                maxBatchSize = 10,
                fastThresholdMs = 2000,
                slowThresholdMs = 5000,
            ),
        )
        val sizer = AdaptiveBatchSizer(pollingProperties, meterRegistry)

        // First, ramp up to a stable batch size
        repeat(15) {
            sizer.recordJobCompletion(3000) // Between thresholds
        }
        val stableBatchSize = sizer.getCurrentBatchSize()

        // Continue with normal jobs (3000ms = between thresholds)
        repeat(10) {
            sizer.recordJobCompletion(3000)
        }

        // Batch size should remain stable
        val currentBatchSize = sizer.getCurrentBatchSize()
        assertEquals(
            stableBatchSize,
            currentBatchSize,
            "Batch size should remain stable when processing is normal",
        )
    }

    @Test
    fun `respects minimum batch size boundary`() {
        val pollingProperties = JobPollingProperties(
            adaptiveBatch = AdaptiveBatchProperties(
                minBatchSize = 2,
                maxBatchSize = 10,
                fastThresholdMs = 2000,
                slowThresholdMs = 5000,
            ),
        )
        val sizer = AdaptiveBatchSizer(pollingProperties, meterRegistry)

        assertEquals(2, sizer.getCurrentBatchSize(), "Should start at minBatchSize")

        // Try to force batch size below minimum with very slow jobs
        repeat(50) {
            sizer.recordJobCompletion(10000)
        }

        // Batch size should never go below minBatchSize
        val batchSize = sizer.getCurrentBatchSize()
        val minBatchSize = pollingProperties.adaptiveBatch.minBatchSize
        assertTrue(
            batchSize >= minBatchSize,
            "Batch size should never go below minBatchSize (got $batchSize, min is $minBatchSize)",
        )
    }

    @Test
    fun `respects maximum batch size boundary`() {
        val pollingProperties = JobPollingProperties(
            adaptiveBatch = AdaptiveBatchProperties(
                minBatchSize = 1,
                maxBatchSize = 5,
                fastThresholdMs = 2000,
                slowThresholdMs = 5000,
            ),
        )
        val sizer = AdaptiveBatchSizer(pollingProperties, meterRegistry)

        // Try to force batch size above maximum with very fast jobs
        repeat(50) {
            sizer.recordJobCompletion(100)
        }

        // Batch size should never exceed maxBatchSize
        val batchSize = sizer.getCurrentBatchSize()
        val maxBatchSize = pollingProperties.adaptiveBatch.maxBatchSize
        assertTrue(
            batchSize <= maxBatchSize,
            "Batch size should never exceed maxBatchSize (got $batchSize, max is $maxBatchSize)",
        )
    }

    @Test
    fun `exposes Micrometer metrics`() {
        val pollingProperties = JobPollingProperties(
            adaptiveBatch = AdaptiveBatchProperties(
                minBatchSize = 1,
                maxBatchSize = 10,
                fastThresholdMs = 2000,
                slowThresholdMs = 5000,
            ),
        )
        val sizer = AdaptiveBatchSizer(pollingProperties, meterRegistry)

        // Record some jobs to populate metrics
        sizer.recordJobCompletion(1500)
        sizer.recordJobCompletion(2000)

        // Verify gauges exist and have values
        val emaGauge = meterRegistry.find("epistola.jobs.processing_time_ema_ms").gauge()
        val batchSizeGauge = meterRegistry.find("epistola.jobs.batch_size").gauge()

        assertTrue(emaGauge != null, "EMA gauge should be registered")
        assertTrue(batchSizeGauge != null, "Batch size gauge should be registered")
        assertTrue(emaGauge.value() > 0, "EMA gauge should have a value")
        assertTrue(batchSizeGauge.value() > 0, "Batch size gauge should have a value")
    }

    @Test
    fun `handles first data point correctly`() {
        val pollingProperties = JobPollingProperties(
            adaptiveBatch = AdaptiveBatchProperties(
                minBatchSize = 1,
                maxBatchSize = 10,
                fastThresholdMs = 2000,
                slowThresholdMs = 5000,
            ),
        )
        val sizer = AdaptiveBatchSizer(pollingProperties, meterRegistry)

        // First data point should be used as initial EMA value
        sizer.recordJobCompletion(1500)

        // Verify EMA gauge has the first value
        val emaGauge = meterRegistry.find("epistola.jobs.processing_time_ema_ms").gauge()
        assertTrue(emaGauge != null, "EMA gauge should be registered")
        assertEquals(1500.0, emaGauge.value(), "First data point should be used as initial EMA")
    }

    @Test
    fun `EMA smooths out spikes`() {
        val pollingProperties = JobPollingProperties(
            adaptiveBatch = AdaptiveBatchProperties(
                minBatchSize = 1,
                maxBatchSize = 10,
                fastThresholdMs = 2000,
                slowThresholdMs = 5000,
            ),
        )
        val sizer = AdaptiveBatchSizer(pollingProperties, meterRegistry)

        // Establish a baseline with fast jobs
        repeat(10) {
            sizer.recordJobCompletion(1000)
        }

        val emaGaugeBefore = meterRegistry.find("epistola.jobs.processing_time_ema_ms").gauge()!!
        val emaBefore = emaGaugeBefore.value()

        // Single spike shouldn't drastically change EMA (smoothing effect)
        sizer.recordJobCompletion(10000)

        val emaGaugeAfter = meterRegistry.find("epistola.jobs.processing_time_ema_ms").gauge()!!
        val emaAfter = emaGaugeAfter.value()

        // EMA should have increased but not by the full spike amount
        assertTrue(
            emaAfter > emaBefore,
            "EMA should increase after spike",
        )
        assertTrue(
            emaAfter < 5000,
            "EMA should not jump to spike value due to smoothing (was $emaBefore, spike was 10000, now $emaAfter)",
        )
    }
}

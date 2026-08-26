// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents.batch

import app.epistola.suite.database.DatabasePressureSnapshot
import app.epistola.suite.database.DatabasePressureSource
import app.epistola.suite.documents.DatabasePressureProperties
import app.epistola.suite.documents.JobPollingProperties
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class DatabasePressureAdmissionControllerTest {

    private class MutableSource : DatabasePressureSource {
        var value = DatabasePressureSnapshot(3, 100, 0, false)
        override fun snapshot(nowMs: Long): DatabasePressureSnapshot = value
    }

    private fun controller(source: MutableSource): DatabasePressureAdmissionController = DatabasePressureAdmissionController(
        properties = JobPollingProperties(
            maxConcurrentJobs = 8,
            databasePressure = DatabasePressureProperties(
                observationWindowMs = 10_000,
                minimumSamples = 3,
                slowStatementThresholdMs = 500,
                recoveryStatementThresholdMs = 200,
                minimumConcurrentJobs = 1,
                backoffIntervalMs = 1_000,
                recoveryPeriodMs = 30_000,
                recoveryStepIntervalMs = 10_000,
            ),
        ),
        pressureSource = source,
        meterRegistry = SimpleMeterRegistry(),
    )

    @Test
    fun `sustained database pressure halves new admission capacity with a floor`() {
        val source = MutableSource()
        val controller = controller(source)

        assertEquals(8, controller.effectiveLimit(nowMs = 0))

        source.value = DatabasePressureSnapshot(3, 600, 0, false)
        assertEquals(4, controller.effectiveLimit(nowMs = 1_000))
        assertEquals(4, controller.effectiveLimit(nowMs = 1_500), "backoff is rate limited")
        assertEquals(2, controller.effectiveLimit(nowMs = 2_000))
        assertEquals(1, controller.effectiveLimit(nowMs = 3_000))
        assertEquals(1, controller.effectiveLimit(nowMs = 4_000))
    }

    @Test
    fun `critical database failure pauses then gradually recovers admissions`() {
        val source = MutableSource()
        val controller = controller(source)

        source.value = DatabasePressureSnapshot(3, 600, 0, true)
        assertEquals(0, controller.effectiveLimit(nowMs = 1_000))

        source.value = DatabasePressureSnapshot(3, 100, 0, false)
        assertEquals(0, controller.effectiveLimit(nowMs = 2_000), "healthy period has not elapsed")
        assertEquals(1, controller.effectiveLimit(nowMs = 32_000), "recovery starts at the minimum")
        assertEquals(2, controller.effectiveLimit(nowMs = 42_000), "recovery raises one slot at a time")
    }

    @Test
    fun `pool waiters throttle even when statement latency is not yet high`() {
        val source = MutableSource()
        val controller = controller(source)

        source.value = DatabasePressureSnapshot(0, null, 1, false)
        assertEquals(4, controller.effectiveLimit(nowMs = 1_000))
    }
}

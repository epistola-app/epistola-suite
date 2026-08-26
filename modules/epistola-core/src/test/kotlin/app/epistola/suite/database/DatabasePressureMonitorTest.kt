// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.database

import app.epistola.suite.documents.DatabasePressureProperties
import app.epistola.suite.documents.JobPollingProperties
import app.epistola.suite.time.EpistolaClock
import com.zaxxer.hikari.HikariDataSource
import com.zaxxer.hikari.HikariPoolMXBean
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.ObjectProvider
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DatabasePressureMonitorTest {

    private fun monitor(registry: SimpleMeterRegistry = SimpleMeterRegistry()): DatabasePressureMonitor {
        val registryProvider = mock<ObjectProvider<MeterRegistry>>()
        `when`(registryProvider.getObject()).thenReturn(registry)
        return DatabasePressureMonitor(
            properties = JobPollingProperties(
                databasePressure = DatabasePressureProperties(observationWindowMs = 10_000),
            ),
            meterRegistryProvider = registryProvider,
            dataSource = mock(DataSource::class.java),
        )
    }

    @Test
    fun `records only aggregate statement latency`() {
        val registry = SimpleMeterRegistry()
        val monitor = monitor(registry)

        monitor.recordSuccess(Duration.ofMillis(40))
        monitor.recordSuccess(Duration.ofMillis(80))

        val snapshot = monitor.snapshot(System.currentTimeMillis())
        assertEquals(2, snapshot.sampleCount)
        assertEquals(80, snapshot.p95StatementLatencyMs)
        assertEquals(2, registry.find("epistola.database.statement.duration").timer()!!.count())
    }

    @Test
    fun `recognises cancellation and connectivity failures as critical`() {
        val monitor = monitor()

        monitor.recordFailure(Duration.ofMillis(600), SQLException("cancelled", "57014"))
        assertTrue(monitor.snapshot(System.currentTimeMillis()).criticalFailureObserved)

        monitor.recordFailure(Duration.ofMillis(5), SQLException("constraint", "23505"))
        assertTrue(monitor.snapshot(System.currentTimeMillis()).criticalFailureObserved, "the earlier critical failure remains in the window")
    }

    @Test
    fun `ordinary database errors do not enter critical mode`() {
        val monitor = monitor()

        monitor.recordFailure(Duration.ofMillis(5), SQLException("constraint", "23505"))

        assertFalse(monitor.snapshot(System.currentTimeMillis()).criticalFailureObserved)
    }

    @Test
    fun `latency samples are pruned once the observation window elapses`() {
        val monitor = monitor()
        val start = Instant.parse("2026-01-01T00:00:00Z")

        EpistolaClock.withInstant(start) { monitor.recordSuccess(Duration.ofMillis(40)) }
        EpistolaClock.withInstant(start.plusMillis(11_000)) { monitor.recordSuccess(Duration.ofMillis(50)) }

        val snapshot = monitor.snapshot(start.plusMillis(11_000).toEpochMilli())
        assertEquals(1, snapshot.sampleCount, "the first sample fell outside the 10s window")
    }

    @Test
    fun `critical failure clears once the observation window elapses`() {
        val monitor = monitor()
        val start = Instant.parse("2026-01-01T00:00:00Z")

        EpistolaClock.withInstant(start) {
            monitor.recordFailure(Duration.ofMillis(5), SQLException("cancelled", "57014"))
        }

        assertTrue(monitor.snapshot(start.plusMillis(9_999).toEpochMilli()).criticalFailureObserved, "still within window")
        assertFalse(monitor.snapshot(start.plusMillis(10_001).toEpochMilli()).criticalFailureObserved, "window elapsed")
    }

    @Test
    fun `reports pool waiters from a real HikariDataSource`() {
        val hikariDataSource = mock(HikariDataSource::class.java)
        val poolMXBean = mock(HikariPoolMXBean::class.java)
        `when`(hikariDataSource.hikariPoolMXBean).thenReturn(poolMXBean)
        `when`(poolMXBean.threadsAwaitingConnection).thenReturn(2)

        val registryProvider = mock<ObjectProvider<MeterRegistry>>()
        `when`(registryProvider.getObject()).thenReturn(SimpleMeterRegistry())
        val monitor = DatabasePressureMonitor(
            properties = JobPollingProperties(
                databasePressure = DatabasePressureProperties(observationWindowMs = 10_000),
            ),
            meterRegistryProvider = registryProvider,
            dataSource = hikariDataSource,
        )

        assertEquals(2, monitor.snapshot(System.currentTimeMillis()).poolWaiters)
    }
}

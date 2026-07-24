// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.testing

import app.epistola.suite.documents.cleanup.PartitionMaintenanceScheduler
import app.epistola.suite.time.EpistolaClock
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

/**
 * Ensures RANGE-partitioned tables (`event_log`, `audit_log`, `documents`, …) have a
 * partition for the **test clock's** month.
 *
 * Partition provisioning follows [EpistolaClock] — [PartitionMaintenanceScheduler]
 * derives the current + next month from `EpistolaClock.offsetDateTime()`. But at
 * application startup no test clock is bound, so `EpistolaClock` falls back to the
 * system clock and the scheduler bootstraps partitions against **real wall-clock
 * time**. Integration tests, however, stamp `occurred_at` with the frozen
 * [MutableClock] (default `2026-06-10`). Once real time drifts into a later month
 * than the frozen clock, the frozen month has no partition and every command insert
 * fails with `ERROR: no partition of relation "event_log" found for row`, cascading
 * into virtually every integration/UI test.
 *
 * The fix re-runs the same EpistolaClock-based maintenance once **under the test
 * clock**, so provisioning uses the very clock that stamps the rows. Idempotent
 * (`CREATE TABLE IF NOT EXISTS`), and production is unaffected — there `EpistolaClock`
 * already resolves to system time, so nothing changes.
 */
@TestConfiguration(proxyBeanMethods = false)
class PartitionBootstrapTestConfiguration {
    @Bean
    fun bootstrapTestClockPartitions(scheduler: ObjectProvider<PartitionMaintenanceScheduler>): SmartInitializingSingleton = SmartInitializingSingleton {
        // Only when partition maintenance is enabled (the default). A test that
        // disables `epistola.partitions.enabled` has no scheduler bean and owns
        // its own partition strategy.
        scheduler.ifAvailable { maintenance ->
            EpistolaClock.withClock(MutableClock()) {
                maintenance.maintainPartitions()
            }
        }
    }
}

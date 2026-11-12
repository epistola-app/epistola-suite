// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.time

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Elapsed time for the support work's own budgets: how long a round may run, when the next poll
 * is due. A reading means nothing on its own; only the difference between two readings does, as
 * with [System.nanoTime], which is what [SYSTEM] reads.
 *
 * Not the wall clock: timestamps come from `EpistolaClock` (docs/clock.md). This is its counterpart
 * for durations, so a test can let a budget run out by advancing a fake instead of sleeping, and a
 * slow machine cannot run one out in a test that is not about it. `SupportTimeUsageTest` keeps
 * `System.nanoTime()` and `Thread.sleep` out of the support modules' production code elsewhere.
 */
fun interface SupportTime {
    /** A monotonic reading in nanoseconds. */
    fun nanoTime(): Long

    companion object {
        /** The JVM's monotonic clock. */
        val SYSTEM: SupportTime = SupportTime { System.nanoTime() }
    }
}

/** The support work's [SupportTime]; a test replaces it with a `@Primary` fake it advances. */
@Configuration(proxyBeanMethods = false)
class SupportTimeConfiguration {
    @Bean
    fun supportTime(): SupportTime = SupportTime.SYSTEM
}

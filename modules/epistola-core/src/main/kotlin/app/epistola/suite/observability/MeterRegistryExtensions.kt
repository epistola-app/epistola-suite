// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.observability

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer

/**
 * Records execution count, duration, and outcome for a background scheduled task
 * (maintenance crons, cleanups, sync loops).
 *
 * Without this a cron that silently stops firing — or starts failing every run —
 * is invisible across a remote fleet. Times [block] and emits the timer
 * `epistola.scheduled.task.duration` tagged `task` and `outcome`
 * (`success` | `failure`). Alert on the **absence** of recent samples to catch a
 * stalled scheduler, and on the failure rate to catch a broken one. Re-throws so
 * existing error handling/logging is unaffected.
 */
fun <T> MeterRegistry.recordScheduledTask(task: String, block: () -> T): T {
    val sample = Timer.start(this)
    var outcome = "success"
    try {
        return block()
    } catch (e: Throwable) {
        outcome = "failure"
        throw e
    } finally {
        sample.stop(
            Timer.builder("epistola.scheduled.task.duration")
                .tag("task", task)
                .tag("outcome", outcome)
                .register(this),
        )
    }
}

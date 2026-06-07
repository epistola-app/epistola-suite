package app.epistola.suite.observability

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component

/**
 * Records execution count, duration, and outcome for background scheduled tasks
 * (maintenance crons, cleanups, sync loops).
 *
 * Without this a cron that silently stops firing — or starts failing every run
 * — is invisible across a remote fleet. Emits the timer
 * `epistola.scheduled.task.duration` tagged `task` and `outcome`
 * (`success` | `failure`). Alert on the **absence** of recent samples to catch
 * a stalled scheduler, and on the failure rate to catch a broken one.
 */
@Component
class ScheduledTaskMetrics(private val meterRegistry: MeterRegistry) {

    /**
     * Run [block], timing it and tagging the recorded sample with the outcome.
     * Re-throws so existing error handling/logging is unaffected.
     */
    fun record(task: String, block: () -> Unit) {
        val sample = Timer.start(meterRegistry)
        var outcome = "success"
        try {
            block()
        } catch (e: Throwable) {
            outcome = "failure"
            throw e
        } finally {
            sample.stop(
                Timer.builder("epistola.scheduled.task.duration")
                    .tag("task", task)
                    .tag("outcome", outcome)
                    .register(meterRegistry),
            )
        }
    }
}

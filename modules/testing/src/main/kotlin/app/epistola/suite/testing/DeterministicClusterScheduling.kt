package app.epistola.suite.testing

import app.epistola.suite.cluster.ClusterNodeHeartbeatScheduler
import app.epistola.suite.cluster.ClusterSchedulingDriver
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskScheduler
import app.epistola.suite.cluster.timers.ClusterTimerScheduler
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import java.time.Duration

/**
 * Deterministic scheduling substrate for integration tests.
 *
 * Production ticks the cluster scheduling engines from wall-clock `@Scheduled`
 * delays. That substrate can never participate in test time: the test clock is
 * bound via `ScopedValue` on the test thread ([EpistolaClockExtension]), so a
 * background scheduler thread always evaluates due-ness against the system
 * clock — and its autonomous ticks race concurrently-running test classes on
 * installation-wide state (timers and scheduled tasks are not tenant-scoped).
 *
 * This driver replaces the loop with explicit, synchronous invocation on the
 * test thread, inside the test's clock and principal scope:
 *
 * ```kotlin
 * // "a day passes and due scheduled work runs"
 * scheduling.advanceTimeBy(Duration.ofHours(25))
 * ```
 *
 * Advancing [IntegrationTestBase.testClock] directly stays pure — time moves,
 * nothing fires — so tests opt into scheduled execution explicitly.
 */
class DeterministicClusterScheduling(
    private val heartbeat: ClusterNodeHeartbeatScheduler,
    private val timers: ClusterTimerScheduler,
    private val tasks: ClusterScheduledTaskScheduler,
) : ClusterSchedulingDriver {

    /**
     * Runs all due timers and scheduled tasks until quiescent — looping so a
     * handler that schedules immediately-due follow-up work in the same instant
     * also runs before this returns.
     */
    fun runDue() {
        heartbeat.heartbeat()
        var rounds = 0
        while (timers.poll() + tasks.poll() > 0) {
            check(++rounds < MAX_ROUNDS) {
                "Cluster scheduling did not quiesce after $MAX_ROUNDS rounds — " +
                    "a handler keeps producing immediately-due work"
            }
        }
    }

    /** Advances the test clock by [duration], then runs everything that became due. */
    fun advanceTimeBy(duration: Duration) {
        EpistolaClockExtension.current().advanceBy(duration)
        runDue()
    }

    private companion object {
        const val MAX_ROUNDS = 100
    }
}

/**
 * Registers [DeterministicClusterScheduling] when the test substrate is
 * selected. Gated on `epistola.cluster.scheduling-substrate=test` (set by
 * [IntegrationTestBase]) so it is mutually exclusive with the production
 * wall-clock driver regardless of configuration-class ordering; a test can opt
 * back into the real loop with
 * `@TestPropertySource(properties = ["epistola.cluster.scheduling-substrate=wall-clock"])`.
 */
@TestConfiguration(proxyBeanMethods = false)
class DeterministicSchedulingTestConfiguration {

    @Bean
    @ConditionalOnProperty(
        prefix = "epistola.cluster",
        name = ["scheduling-substrate"],
        havingValue = "test",
    )
    fun deterministicClusterScheduling(
        heartbeat: ClusterNodeHeartbeatScheduler,
        timers: ClusterTimerScheduler,
        tasks: ClusterScheduledTaskScheduler,
    ): DeterministicClusterScheduling = DeterministicClusterScheduling(heartbeat, timers, tasks)
}

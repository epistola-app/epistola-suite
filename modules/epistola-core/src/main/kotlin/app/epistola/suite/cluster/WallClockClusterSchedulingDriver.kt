// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.cluster

import app.epistola.suite.cluster.schedules.ClusterScheduledTaskScheduler
import app.epistola.suite.cluster.timers.ClusterTimerScheduler
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled

/**
 * Production scheduling substrate: ticks the cluster *poller* engines on fixed
 * wall-clock delays.
 *
 * This class owns only the *when* (the `@Scheduled` wakeups); the engines own
 * the *what* and stay directly invokable. See [ClusterSchedulingDriver].
 *
 * The node heartbeat is deliberately *not* driven here — it runs on the
 * dedicated [ClusterMaintenanceExecutor] thread so a slow handler on this
 * `@Scheduled` pool can never starve liveness (see [ClusterNodeHeartbeatScheduler]).
 */
class WallClockClusterSchedulingDriver(
    private val timers: ClusterTimerScheduler,
    private val tasks: ClusterScheduledTaskScheduler,
) : ClusterSchedulingDriver {

    @Scheduled(fixedDelayString = "\${epistola.cluster.timers.poll-interval-ms:1000}")
    fun timerTick() {
        timers.poll()
    }

    @Scheduled(fixedDelayString = "\${epistola.cluster.scheduled-tasks.poll-interval-ms:1000}")
    fun scheduledTaskTick() {
        tasks.poll()
    }
}

/**
 * Registers the wall-clock driver unless a different scheduling substrate is
 * selected.
 *
 * Gated on `epistola.cluster.scheduling-substrate=wall-clock` (the default) so
 * it is mutually exclusive with the deterministic test driver regardless of
 * configuration-class ordering (an `@ConditionalOnMissingBean`-only default can
 * race an imported test configuration and register both). The
 * `@ConditionalOnMissingBean` is kept so an explicit driver override still wins.
 */
@Configuration
@EnableScheduling
class ClusterSchedulingConfiguration {

    @Bean
    @ConditionalOnMissingBean(ClusterSchedulingDriver::class)
    @ConditionalOnProperty(
        prefix = "epistola.cluster",
        name = ["scheduling-substrate"],
        havingValue = ClusterProperties.SUBSTRATE_WALL_CLOCK,
        matchIfMissing = true,
    )
    fun wallClockClusterSchedulingDriver(
        timers: ClusterTimerScheduler,
        tasks: ClusterScheduledTaskScheduler,
    ): WallClockClusterSchedulingDriver = WallClockClusterSchedulingDriver(timers, tasks)
}

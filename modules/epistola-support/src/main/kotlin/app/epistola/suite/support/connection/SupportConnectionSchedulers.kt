// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.connection

import app.epistola.suite.cluster.schedules.ClusterScheduledTask
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskDefinition
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskExecutionScope
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskHandler
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskSchedule
import app.epistola.suite.support.SupportProperties
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.stereotype.Component

/**
 * Registers when needed and refreshes the stored status, on one node per round, every
 * `epistola.support.check-interval`. Only while the support connection is on.
 */
@Component
@ConditionalOnProperty(prefix = "epistola.support", name = ["enabled"], havingValue = "true")
class SupportConnectionCheckScheduler(
    private val connection: DefaultSupportConnection,
    private val properties: SupportProperties,
    private val work: SupportBackgroundWork,
) : ClusterScheduledTaskHandler {
    override val taskType: String = TASK_TYPE

    @Bean
    fun supportConnectionCheckTaskDefinition(): ClusterScheduledTaskDefinition = ClusterScheduledTaskDefinition(
        taskKey = TASK_KEY,
        routingKey = "system:$TASK_KEY",
        taskType = TASK_TYPE,
        schedule = ClusterScheduledTaskSchedule.FixedDelay(properties.checkInterval.toMillis()),
        executionScope = ClusterScheduledTaskExecutionScope.SINGLE_OWNER,
        // Not on a node whose clock is off: there the check could never learn the grants.
        requiredCapability = SupportCluster.CAPABILITY,
    )

    /** Hands the check to [SupportBackgroundWork]: the scheduler thread never waits on the support service. */
    override fun handle(task: ClusterScheduledTask) {
        work.launch(WORK_KEY) { connection.checkNow() }
    }

    companion object {
        const val TASK_KEY = "support.connection.check"
        const val TASK_TYPE = "support.connection.check"

        /** The [SupportBackgroundWork] key of a check or registration, scheduled or asked for. */
        const val WORK_KEY = "support.connection.check"
    }
}

/**
 * Keeps a token fresh on every node while the connection is pending or active, so
 * [SupportConnection.cachedAccessToken] has one to give and a newly granted scope shows within a
 * token's lifetime. Only while the support connection is on.
 */
@Component
@ConditionalOnProperty(prefix = "epistola.support", name = ["enabled"], havingValue = "true")
class SupportTokenRefreshScheduler(
    private val connection: DefaultSupportConnection,
    private val work: SupportBackgroundWork,
) : ClusterScheduledTaskHandler {
    override val taskType: String = TASK_TYPE

    @Bean
    fun supportTokenRefreshTaskDefinition(): ClusterScheduledTaskDefinition = ClusterScheduledTaskDefinition(
        taskKey = TASK_KEY,
        routingKey = "system:$TASK_KEY",
        taskType = TASK_TYPE,
        schedule = ClusterScheduledTaskSchedule.FixedDelay(INTERVAL_MS),
        executionScope = ClusterScheduledTaskExecutionScope.EACH_CAPABLE_NODE,
    )

    /** Hands the refresh to [SupportBackgroundWork]: the scheduler thread never waits on the support service. */
    override fun handle(task: ClusterScheduledTask) {
        work.launch(TASK_KEY) { connection.refreshTokenIfDue() }
    }

    companion object {
        const val TASK_KEY = "support.connection.token"
        const val TASK_TYPE = "support.connection.token"
        const val INTERVAL_MS = 30_000L
    }
}

/**
 * Keeps a token fresh on every node for each connection other than Epistola support's that registers
 * through OAuth registration (ADR 0053 §1), as [SupportTokenRefreshScheduler] does for Epistola
 * support's. Independent of `epistola.support.enabled`.
 */
@Component
class OtherSupportTokenRefreshScheduler(
    private val registry: SupportConnectionRegistry,
    private val work: SupportBackgroundWork,
) : ClusterScheduledTaskHandler {
    override val taskType: String = TASK_TYPE

    @Bean
    fun otherSupportTokenRefreshTaskDefinition(): ClusterScheduledTaskDefinition = ClusterScheduledTaskDefinition(
        taskKey = TASK_KEY,
        routingKey = "system:$TASK_KEY",
        taskType = TASK_TYPE,
        schedule = ClusterScheduledTaskSchedule.FixedDelay(SupportTokenRefreshScheduler.INTERVAL_MS),
        executionScope = ClusterScheduledTaskExecutionScope.EACH_CAPABLE_NODE,
    )

    override fun handle(task: ClusterScheduledTask) {
        work.launch(TASK_KEY) { refreshAll() }
    }

    /** Refreshes each one's token; one that fails does not hold back the others. */
    fun refreshAll() {
        registry.others().filterIsInstance<DefaultSupportConnection>().forEach { runCatching { it.refreshTokenIfDue() } }
    }

    companion object {
        const val TASK_KEY = "support.connection.token.others"
        const val TASK_TYPE = "support.connection.token.others"
    }
}

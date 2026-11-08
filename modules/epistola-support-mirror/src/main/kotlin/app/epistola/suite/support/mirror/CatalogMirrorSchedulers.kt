// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.mirror

import app.epistola.suite.cluster.schedules.ClusterScheduledTask
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskDefinition
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskExecutionScope
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskHandler
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskSchedule
import app.epistola.suite.cluster.timers.ClusterTimer
import app.epistola.suite.cluster.timers.ClusterTimerHandler
import app.epistola.suite.cluster.timers.ClusterTimerResult
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.support.connection.SupportBackgroundWork
import app.epistola.suite.support.connection.SupportCluster
import app.epistola.suite.time.EpistolaClock
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.annotation.Bean
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * The reconcile round, on one node at a time, `epistola.support.mirror.interval` after the last one
 * ended. Exists while the support connection is on, also with the mirror turned off: the round then
 * only withdraws what tenants stopped sharing. It waits for Epistola to grant the `mirror` scope.
 */
@Component
@ConditionalOnExpression("\${epistola.support.enabled:false}")
class CatalogMirrorReconcileScheduler(
    private val reconciler: CatalogMirrorReconciler,
    private val properties: CatalogMirrorProperties,
    private val work: SupportBackgroundWork,
) : ClusterScheduledTaskHandler {
    override val taskType: String = TASK_TYPE

    @Bean
    fun catalogMirrorReconcileTaskDefinition(): ClusterScheduledTaskDefinition = ClusterScheduledTaskDefinition(
        taskKey = TASK_KEY,
        routingKey = "system:$TASK_KEY",
        taskType = TASK_TYPE,
        schedule = ClusterScheduledTaskSchedule.FixedDelay(properties.interval.toMillis()),
        executionScope = ClusterScheduledTaskExecutionScope.SINGLE_OWNER,
        // Never owned by a node whose clock is off, which could not send: the other nodes run it.
        requiredCapability = SupportCluster.CAPABILITY,
    )

    /** Hands the round to [SupportBackgroundWork]: the scheduler thread never waits on the support service. */
    override fun handle(task: ClusterScheduledTask) {
        work.launch(TASK_KEY) { reconciler.reconcileAll() }
    }

    companion object {
        const val TASK_KEY = "support.mirror.reconcile"
        const val TASK_TYPE = "support.mirror.reconcile"
    }
}

/**
 * "Sync now": a one-shot cluster timer per tenant, so the sync runs in the background on whichever
 * node takes it. Always registered, so a timer scheduled before a restart that turned the mirror off
 * is still consumed; the reconciler then does nothing.
 */
@Component
class CatalogMirrorSyncTimerHandler(
    private val reconciler: CatalogMirrorReconciler,
    private val work: SupportBackgroundWork,
) : ClusterTimerHandler {
    override val timerType: String = TIMER_TYPE

    /**
     * Hands the sync to [SupportBackgroundWork], so the timer thread never waits on the support
     * service. A tenant another round holds is tried again a minute later, rather than raced.
     */
    override fun handle(timer: ClusterTimer): ClusterTimerResult {
        val tenantKey = timer.tenantKey ?: return ClusterTimerResult.Complete
        if (reconciler.isClaimed(tenantKey)) return ClusterTimerResult.Reschedule(EpistolaClock.offsetDateTime().plus(BUSY_RETRY))
        if (!work.launch("$TIMER_TYPE:${tenantKey.value}") { reconciler.syncTenant(tenantKey) }) {
            return ClusterTimerResult.Reschedule(EpistolaClock.offsetDateTime().plus(BUSY_RETRY))
        }
        return ClusterTimerResult.Complete
    }

    companion object {
        const val TIMER_TYPE = "support.mirror.sync"

        /** How long a "Sync now" waits before it tries again a tenant another round holds. */
        val BUSY_RETRY: Duration = Duration.ofMinutes(1)

        fun timerKey(tenantKey: TenantKey): String = "$TIMER_TYPE:${tenantKey.value}"
    }
}

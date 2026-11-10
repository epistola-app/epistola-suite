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
import org.jdbi.v3.core.Jdbi
import org.springframework.context.annotation.Bean
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID

/**
 * The reconcile round over every connection the mirror is selected for, on one node at a time,
 * `epistola.support.mirror.interval` after the last one ended. Also with the mirror turned off: the
 * round then only withdraws what tenants stopped sharing. Each connection waits for its `mirror` scope.
 */
@Component
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
        work.launch(TASK_KEY) { reconciler.reconcileEvery() }
    }

    companion object {
        const val TASK_KEY = "support.mirror.reconcile"
        const val TASK_TYPE = "support.mirror.reconcile"
    }
}

/**
 * "Sync now": a one-shot cluster timer per connection and tenant, so the sync runs in the background
 * on whichever node takes it. The connection is in the timer's payload. Always registered, so a timer scheduled before a restart that turned the mirror off
 * is still consumed; the reconciler then does nothing.
 */
@Component
class CatalogMirrorSyncTimerHandler(
    private val reconciler: CatalogMirrorReconciler,
    private val work: SupportBackgroundWork,
    private val jdbi: Jdbi,
) : ClusterTimerHandler {
    override val timerType: String = TIMER_TYPE

    /**
     * Hands the sync to [SupportBackgroundWork], so the timer thread never waits on the support
     * service. A tenant another round holds is tried again a minute later, rather than raced.
     */
    override fun handle(timer: ClusterTimer): ClusterTimerResult {
        val tenantKey = timer.tenantKey ?: return ClusterTimerResult.Complete
        val connectionId = (timer.payload[CONNECTION] as? String)?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return ClusterTimerResult.Complete
        if (reconciler.isClaimed(tenantKey, connectionId)) return ClusterTimerResult.Reschedule(EpistolaClock.offsetDateTime().plus(BUSY_RETRY))
        if (!work.launch(timerKey(connectionId, tenantKey)) { sync(connectionId, tenantKey) }) {
            return ClusterTimerResult.Reschedule(EpistolaClock.offsetDateTime().plus(BUSY_RETRY))
        }
        return ClusterTimerResult.Complete
    }

    /**
     * Runs the sync; when another round claimed the tenant between [handle]'s check and the claim, asks
     * for it again, so a "Sync now" is never lost (that round's release clears the request).
     */
    internal fun sync(connectionId: UUID, tenantKey: TenantKey): TenantReconcileResult = reconciler.syncTenant(tenantKey, connectionId).also {
        if (it == TenantReconcileResult.BUSY) scheduleSync(jdbi, connectionId, tenantKey)
    }

    companion object {
        const val TIMER_TYPE = "support.mirror.sync"

        /** How long a "Sync now" waits before it tries again a tenant another round holds. */
        val BUSY_RETRY: Duration = Duration.ofMinutes(1)

        /** The payload key naming the connection to sync with. */
        const val CONNECTION = "connection"

        fun timerKey(connectionId: UUID, tenantKey: TenantKey): String = "$TIMER_TYPE:$connectionId:${tenantKey.value}"
    }
}

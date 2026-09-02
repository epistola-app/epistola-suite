// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.cluster

import app.epistola.suite.cluster.schedules.ClusterScheduledTask
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskDefinition
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskExecutionScope
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskHandler
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskSchedule
import app.epistola.suite.observability.NodeIdentity
import app.epistola.suite.observability.recordScheduledTask
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.stereotype.Component

/**
 * Daily purge of `cluster_nodes` rows whose heartbeat is older than
 * [ClusterProperties.effectiveStaleNodeRetention], together with the scheduled-task
 * registrations and per-node task state those dead nodes left behind.
 *
 * Without this the registry only ever grows. [NodeIdentity] derives `node_id` from the
 * pod hostname, so on Kubernetes every Deployment rollout registers a whole new set of
 * rows and nothing removed the old ones: an installation accumulates a permanent row per
 * pod that ever ran, plus roughly one registration row per pod *per scheduled-task
 * definition*. They are already excluded from every claim path by the
 * `last_seen_at > :activeSince` filters, but they pile up in the registry and dominate
 * the Cluster operations page.
 *
 * ## Why retention is measured in days
 *
 * Deleting a node row is not merely cosmetic. `ClusterScheduledTaskRegistry`'s
 * `LIVE_REGISTRATION_EXISTS` inner-joins `cluster_scheduled_task_registrations` to
 * `cluster_nodes`, so a purged node stops vouching for its scheduled-task definitions the
 * instant its row disappears — exactly as if it had been stale forever. A retention
 * window shorter than `reconciliationGracePeriodMs` would therefore let this reaper
 * retire live definitions ahead of the reconciler. [ClusterProperties.effectiveStaleNodeRetention]
 * clamps the window so that is unreachable by configuration; this class always reads the
 * clamped value, never [ClusterNodeReaperProperties.staleNodeRetention] directly.
 *
 * Disabling the reaper (`epistola.cluster.node-reaper.enabled=false`) removes the
 * scheduled-task definition bean, so this node stops vouching for
 * `core.stale-cluster-node-reaper` and the reconciler retires that row after the grace
 * period. That is the ordinary lifecycle of a withdrawn definition, not data loss: the
 * row is re-created on the next startup with the reaper enabled.
 */
@Component
@ConditionalOnProperty(
    prefix = "epistola.cluster.node-reaper",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class StaleClusterNodeReaper(
    private val nodeRegistry: ClusterNodeRegistry,
    private val meterRegistry: MeterRegistry,
    private val properties: ClusterProperties,
) : ClusterScheduledTaskHandler {
    private val logger = LoggerFactory.getLogger(javaClass)

    override val taskType: String = TASK_TYPE

    @Bean
    fun staleClusterNodeReaperScheduledTaskDefinition(): ClusterScheduledTaskDefinition = ClusterScheduledTaskDefinition(
        taskKey = TASK_KEY,
        routingKey = ROUTING_KEY,
        taskType = TASK_TYPE,
        schedule = ClusterScheduledTaskSchedule.Cron(properties.nodeReaper.cron),
        executionScope = ClusterScheduledTaskExecutionScope.SINGLE_OWNER,
    )

    override fun handle(task: ClusterScheduledTask) {
        reap()
    }

    fun reap() = meterRegistry.recordScheduledTask("stale-cluster-node-reaper") {
        val retention = properties.effectiveStaleNodeRetention()
        val purge = deleteStaleNodes()
        if (purge.isEmpty) {
            logger.debug("No cluster nodes stale beyond {} to reap", retention)
        } else {
            logger.info(
                "Reaped {} cluster node(s) unseen for longer than {}, " +
                    "cascading {} scheduled-task registration(s) and {} per-node task state row(s)",
                purge.nodes,
                retention,
                purge.registrations,
                purge.nodeStates,
            )
        }
    }

    /**
     * Runs the purge and reports what it removed.
     *
     * Public so the integration test can trigger it directly instead of waiting for the
     * cron, and observe the cascade counts without re-querying.
     */
    fun deleteStaleNodes(): StaleClusterNodePurge = nodeRegistry.purgeNodesLastSeenBefore(properties.effectiveStaleNodeRetention())

    companion object {
        const val TASK_KEY = "core.stale-cluster-node-reaper"
        const val ROUTING_KEY = "system:core.stale-cluster-node-reaper"
        const val TASK_TYPE = "core.stale-cluster-node-reaper"
    }
}

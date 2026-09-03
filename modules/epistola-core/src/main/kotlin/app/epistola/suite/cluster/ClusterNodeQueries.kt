// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.cluster

import app.epistola.suite.common.NotAudited
import app.epistola.suite.common.NotEventLogged
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.security.SystemInternal
import app.epistola.suite.time.EpistolaClock
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Lists all known cluster nodes (live and stale) from the node registry.
 *
 * `SystemInternal` like the other cluster reads ([app.epistola.suite.cluster.timers.ListClusterTimers],
 * [app.epistola.suite.cluster.schedules.ListClusterScheduledTasks]) — the UI layer gates
 * the Operations page; the query itself carries no tenant scope.
 */
data object ListClusterNodes :
    Query<List<ClusterNode>>,
    SystemInternal

@Component
class ListClusterNodesHandler(
    private val registry: ClusterNodeRegistry,
) : QueryHandler<ListClusterNodes, List<ClusterNode>> {
    override fun handle(query: ListClusterNodes): List<ClusterNode> = registry.allNodes()
}

/**
 * Records a heartbeat for the current node and returns its fresh registry row.
 *
 * The cluster status page dispatches this on open so the viewing node always shows as
 * live. Infrastructure liveness, not a domain action — excluded from the audit trail
 * and the event stream.
 */
data object RecordClusterHeartbeat :
    Command<ClusterNode>,
    SystemInternal,
    NotAudited,
    NotEventLogged

@Component
class RecordClusterHeartbeatHandler(
    private val registry: ClusterNodeRegistry,
) : CommandHandler<RecordClusterHeartbeat, ClusterNode> {
    override fun handle(command: RecordClusterHeartbeat): ClusterNode = registry.heartbeat()
}

/**
 * Removes one dead node from the cluster registry at an operator's request.
 *
 * The registry is installation-wide, but the *authorization* to maintain it is a tenant
 * permission — the same way the Cluster operations page itself is gated. [tenantKey] is
 * therefore the permission scope, not a data scope.
 *
 * Only a node unseen for [ClusterProperties.forgettableNodeAge] can be forgotten — a far
 * longer window than the `stale` badge, because this cascade deletes scheduled-task
 * registration rows that only a restart re-creates.
 * [ClusterNodeRegistry.forgetNode] re-checks the age in the same statement that deletes,
 * so the guard holds even against a concurrent heartbeat.
 *
 * Audited: this is a deliberate operator action, unlike the infrastructural
 * [RecordClusterHeartbeat]. Not event-logged — nothing downstream reacts to it.
 */
data class ForgetClusterNode(
    val nodeId: String,
    override val tenantKey: TenantKey,
) : Command<StaleClusterNodePurge>,
    RequiresPermission,
    NotEventLogged {
    override val permission get() = Permission.DIAGNOSTICS_MANAGE
}

@Component
class ForgetClusterNodeHandler(
    private val registry: ClusterNodeRegistry,
    private val properties: ClusterProperties,
) : CommandHandler<ForgetClusterNode, StaleClusterNodePurge> {
    override fun handle(command: ForgetClusterNode): StaleClusterNodePurge {
        val node = registry.findNode(command.nodeId)
            ?: throw ValidationException(
                field = "nodeId",
                message = "Cluster node '${command.nodeId}' is not registered.",
                code = ValidationCode.CLUSTER_NODE_NOT_FOUND,
            )

        val purge = registry.forgetNode(node.nodeId)
        if (purge.nodes == 0) {
            // The delete re-checks the age, so reaching here means the node has been seen
            // too recently to forget. Say how recently and what the bar is, rather than
            // silently doing nothing — "stale" on the page is a much shorter window than
            // "forgettable", and that difference is the whole point of the guard.
            val age = Duration.between(node.lastSeenAt.toInstant(), EpistolaClock.instant())
            throw ValidationException(
                field = "nodeId",
                message = "Cluster node '${command.nodeId}' was last seen ${compact(age)} ago; " +
                    "a node can be forgotten once it has been unseen for " +
                    "${compact(properties.forgettableNodeAge())}.",
                code = ValidationCode.CLUSTER_NODE_NOT_FORGETTABLE,
            )
        }
        return purge
    }

    /** "45s" / "4m" / "2h" / "3d" — coarse on purpose; this only has to orient an operator. */
    private fun compact(duration: Duration): String {
        val seconds = duration.seconds.coerceAtLeast(0)
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3_600 -> "${seconds / 60}m"
            seconds < 86_400 -> "${seconds / 3_600}h"
            else -> "${seconds / 86_400}d"
        }
    }
}

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
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import org.springframework.stereotype.Component

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
 * Only a node that is already stale can be forgotten: an active node would simply
 * re-register on its next heartbeat, and removing a live node's row briefly stops it
 * claiming cluster work. [ClusterNodeRegistry.forgetNode] re-checks liveness in the same
 * statement that deletes, so the guard holds even against a concurrent heartbeat.
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
            // The delete re-checks liveness, so reaching here means the node is still
            // heartbeating — report that rather than silently doing nothing.
            throw ValidationException(
                field = "nodeId",
                message = "Cluster node '${command.nodeId}' is still active and cannot be forgotten.",
                code = ValidationCode.CLUSTER_NODE_ACTIVE,
            )
        }
        return purge
    }
}

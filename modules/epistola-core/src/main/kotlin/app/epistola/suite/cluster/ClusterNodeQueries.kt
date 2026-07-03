package app.epistola.suite.cluster

import app.epistola.suite.common.NotAudited
import app.epistola.suite.common.NotEventLogged
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.SystemInternal
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

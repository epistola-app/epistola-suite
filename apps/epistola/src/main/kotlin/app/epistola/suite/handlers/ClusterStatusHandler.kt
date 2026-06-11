package app.epistola.suite.handlers

import app.epistola.suite.cluster.ClusterNode
import app.epistola.suite.cluster.ClusterNodeRegistry
import app.epistola.suite.cluster.ClusterProperties
import app.epistola.suite.cluster.schedules.ClusterScheduledTask
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskExecutionScope
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskNodeState
import app.epistola.suite.cluster.schedules.ListClusterScheduledTaskNodeStates
import app.epistola.suite.cluster.schedules.ListClusterScheduledTasks
import app.epistola.suite.cluster.timers.ClusterTimer
import app.epistola.suite.cluster.timers.ListClusterTimers
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.mediator.query
import app.epistola.suite.time.EpistolaClock
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

/**
 * Renders the Operations -> Cluster page.
 *
 * The page combines the live node registry with mediator queries for timers and
 * scheduled tasks. Recording a heartbeat here keeps the current node fresh when
 * an operator opens the page, while timer/task reads stay on the public
 * command-query boundary instead of depending on internal registries.
 */
@Component
class ClusterStatusHandler(
    private val registry: ClusterNodeRegistry,
    private val properties: ClusterProperties,
) {

    fun dashboard(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val report = loadReport()

        return ServerResponse.ok().page("cluster/dashboard") {
            "pageTitle" to "Cluster - Epistola"
            "tenantId" to tenantId.key
            "activeNavSection" to "cluster"
            "report" to report
        }
    }

    fun refresh(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val report = loadReport()

        return request.htmx {
            fragment("cluster/dashboard", "results") {
                "tenantId" to tenantId.key
                "report" to report
            }
            onNonHtmx { redirect("/tenants/${tenantId.key}/cluster") }
        }
    }

    private fun loadReport(): ClusterStatusReport {
        val currentNode = registry.heartbeat()
        val now = EpistolaClock.offsetDateTime()
        val nodes = registry.allNodes().map { node ->
            val ageMs = java.time.Duration.between(node.lastSeenAt, now).toMillis().coerceAtLeast(0)
            ClusterNodeStatus(
                node = node,
                isCurrent = node.nodeId == currentNode.nodeId,
                isActive = ageMs <= properties.idleTimeoutMs,
                ageSeconds = ageMs / MILLIS_PER_SECOND,
            )
        }
        val scheduledTaskNodeStates = ListClusterScheduledTaskNodeStates.query()
        val scheduledTaskNodeStatesByTask = scheduledTaskNodeStates.groupBy { it.taskKey }

        return ClusterStatusReport(
            nodes = nodes,
            timers = ListClusterTimers().query(),
            scheduledTasks = ListClusterScheduledTasks.query().map { task ->
                val statesByNode = scheduledTaskNodeStatesByTask[task.taskKey].orEmpty().associateBy { it.nodeId }
                val nodeStates = if (task.executionScope == ClusterScheduledTaskExecutionScope.EACH_CAPABLE_NODE) {
                    nodes
                        .filter { task.requiredCapability in it.node.capabilities }
                        .sortedBy { it.node.nodeId }
                        .map { node ->
                            val state = statesByNode[node.node.nodeId]
                            ClusterScheduledTaskNodeStatus(
                                nodeId = node.node.nodeId,
                                nodeStatusLabel = node.statusLabel,
                                taskStatusLabel = taskStatusLabel(node, state, now),
                                nextDueAt = state?.nextDueAt ?: task.nextDueAt,
                                leaseExpiresAt = state?.leaseExpiresAt,
                                consecutiveFailures = state?.consecutiveFailures ?: 0,
                            )
                        }
                } else {
                    emptyList()
                }
                ClusterScheduledTaskStatus(
                    task = task,
                    nodeStates = nodeStates,
                )
            },
            heartbeatIntervalMs = properties.heartbeatIntervalMs,
            idleTimeoutMs = properties.idleTimeoutMs,
            activeCount = nodes.count { it.isActive },
            staleCount = nodes.count { !it.isActive },
        )
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
    }

    private fun taskStatusLabel(
        node: ClusterNodeStatus,
        state: ClusterScheduledTaskNodeState?,
        now: java.time.OffsetDateTime,
    ): String = when {
        !node.isActive -> "stale"
        state?.leaseExpiresAt?.isAfter(now) == true -> "leased"
        (state?.consecutiveFailures ?: 0) > 0 -> "retrying"
        state == null -> "pending"
        else -> "scheduled"
    }
}

/**
 * Read model consumed by the cluster dashboard template.
 */
data class ClusterStatusReport(
    val nodes: List<ClusterNodeStatus>,
    val timers: List<ClusterTimer>,
    val scheduledTasks: List<ClusterScheduledTaskStatus>,
    val heartbeatIntervalMs: Long,
    val idleTimeoutMs: Long,
    val activeCount: Int,
    val staleCount: Int,
) {
    val totalCount: Int = nodes.size
    val heartbeatIntervalSeconds: Long = heartbeatIntervalMs / 1_000L
    val idleTimeoutSeconds: Long = idleTimeoutMs / 1_000L
}

/**
 * Presentation state for one scheduled task and its per-node execution state.
 */
data class ClusterScheduledTaskStatus(
    val task: ClusterScheduledTask,
    val nodeStates: List<ClusterScheduledTaskNodeStatus>,
) {
    val eachCapableNode: Boolean = task.executionScope == ClusterScheduledTaskExecutionScope.EACH_CAPABLE_NODE
    val effectiveNextDueAt: java.time.OffsetDateTime = nodeStates.minOfOrNull { it.nextDueAt } ?: task.nextDueAt
    val retired: Boolean = task.retiredAt != null

    /**
     * Lifecycle label shown in Operations. A retired task's definition has
     * disappeared from code and the row is awaiting purge; a disabled task was
     * turned off but still has a code definition.
     */
    val statusLabel: String = when {
        retired -> "retired"
        !task.enabled -> "disabled"
        else -> "active"
    }
}

/**
 * Presentation state for one node that is relevant to an all-node scheduled task.
 */
data class ClusterScheduledTaskNodeStatus(
    val nodeId: String,
    val nodeStatusLabel: String,
    val taskStatusLabel: String,
    val nextDueAt: java.time.OffsetDateTime,
    val leaseExpiresAt: java.time.OffsetDateTime?,
    val consecutiveFailures: Int,
)

/**
 * Presentation state for one registered cluster node.
 */
data class ClusterNodeStatus(
    val node: ClusterNode,
    val isCurrent: Boolean,
    val isActive: Boolean,
    val ageSeconds: Long,
) {
    val statusLabel: String = if (isActive) "active" else "stale"
    val capabilitiesLabel: String = node.capabilities.ifEmpty { listOf("suite") }.joinToString(", ")
    val versionLabel: String = node.version ?: "-"
    val ageLabel: String = when {
        ageSeconds < 1 -> "just now"
        ageSeconds == 1L -> "1s ago"
        ageSeconds < 60 -> "${ageSeconds}s ago"
        else -> "${ageSeconds / 60}m ago"
    }
}
